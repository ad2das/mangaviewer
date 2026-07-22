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

#include "SwappyCommon.h"

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <pthread.h>
#include <sys/resource.h>

#include "Settings.h"
#include "FixedDisplayTimingAuthority.h"
#include "FixedExternalSubmissionContract.h"
#include "Thread.h"
#include "Trace.h"

#define LOG_TAG "SwappyCommon"
#include "SwappyLog.h"

namespace swappy {

using std::chrono::milliseconds;
using std::chrono::nanoseconds;

namespace {

constexpr std::int64_t kFixedNinetyHzPeriodNanos = 11'111'111LL;
constexpr std::int64_t kFixedRefreshToleranceNanos = 100'000LL;

std::int64_t monotonicNowNanos() noexcept {
    timespec now{};
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0;
    return static_cast<std::int64_t>(now.tv_sec) * 1'000'000'000LL +
           now.tv_nsec;
}

int absoluteMonotonicWaitOnce(std::int64_t targetNanos) noexcept {
    if (targetNanos <= 0) return EINVAL;
    // The fixed Case-2 gate is less than one 90 Hz period away.  Android
    // Emulator's host-backed clock_nanosleep can overshoot this short interval
    // by roughly half a refresh, which closes the immutable opportunity before
    // the transport claim can begin.  Stay on the already dedicated render
    // owner and observe the same absolute target exactly once; there is no
    // retarget, retry, or second frame attempt.
    for (;;) {
        const std::int64_t nowNanos = monotonicNowNanos();
        if (nowNanos <= 0) return EINVAL;
        if (nowNanos >= targetNanos) return 0;
#if defined(__aarch64__)
        __asm__ __volatile__("yield");
#elif defined(__x86_64__)
        __asm__ __volatile__("pause");
#else
        __asm__ __volatile__("" ::: "memory");
#endif
    }
}

void copyPlanToTelemetry(const FixedPhasePlanInput& input,
                         const FixedPhasePlan& plan,
                         SwappyFixedPhaseTelemetry* out) {
    if (!out) return;
    out->outcome = static_cast<std::int32_t>(plan.outcome);
    out->planValid = plan.valid ? 1 : 0;
    out->phaseMissProven = plan.phaseMissProven ? 1 : 0;
    out->absoluteWaitRequired = plan.absoluteWaitRequired ? 1 : 0;
    out->refreshPeriodNanos = input.refreshPeriodNanos;
    out->appVsyncOffsetNanos = input.appVsyncOffsetNanos;
    out->presentationDeadlineNanos = input.presentationDeadlineNanos;
    out->acceptedFrameTimeNanos = input.acceptedFrameTimeNanos;
    out->acceptedFrameIndex = input.acceptedFrameIndex;
    out->decisionNanos = input.decisionNanos;
    out->physicalRefreshNanos = plan.physicalRefreshNanos;
    out->earliestPresentationNanos = plan.earliestPresentationNanos;
    out->earliestCutoffNanos = plan.earliestCutoffNanos;
    out->missedPresentationNanos = plan.missedPresentationNanos;
    out->plannedPresentationNanos = plan.plannedPresentationNanos;
    out->plannedCutoffNanos = plan.plannedCutoffNanos;
    out->phaseOpenNanos = plan.phaseOpenNanos;
    // The Oracle intentionally keeps the Case-1-only miss-window member at
    // zero.  Schema telemetry instead exposes the active exclusive pre-swap
    // limit for both cases: C1 uses its planned cutoff and C2 uses the proved
    // miss-window latest start.  This preserves Oracle bit-compatibility while
    // preventing a valid C1 plan from publishing an incomplete phase proof.
    out->latestSwapStartExclusiveNanos =
        plan.phaseMissProven ? plan.latestSwapStartExclusiveNanos
                             : plan.plannedCutoffNanos;
    out->phaseWaitNanos = plan.phaseWaitNanos;
    out->plannedTargetFrame = plan.plannedTargetFrame;
}

bool fixedOpportunityRendererObservedExact(
        const FixedPublishedOpportunity& opportunity) noexcept {
    const SwappyFixedWakeNotice& notice = opportunity.wakeNotice;
    return notice.structSize == sizeof(SwappyFixedWakeNotice) &&
        notice.version == SWAPPY_FIXED_WAKE_NOTICE_VERSION &&
        notice.workGeneration == opportunity.workGeneration &&
        notice.reservationSequence == opportunity.reservationSequence &&
        notice.opportunitySequence == opportunity.opportunitySequence &&
        notice.candidateSequence == opportunity.candidateSequence &&
        notice.wakeReason == SWAPPY_FIXED_WAKE_JOIN_OPEN &&
        notice.opportunityPublishNanos == opportunity.publishNanos &&
        notice.wakeDispatchNanos >= notice.opportunityPublishNanos &&
        notice.rendererCallbackObservedNanos >= notice.wakeDispatchNanos &&
        notice.rendererCallbackObservedNanos > 0;
}

bool fixedDemandLedgerConserved(
        const FixedDemandLedgerSnapshot& snapshot) noexcept {
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

bool fixedDemandMutationOwnsOpportunity(
        const FixedDemandMutationResult& mutation) noexcept {
    return mutation.accepted &&
        (mutation.outstandingMask & FIXED_DEMAND_OPPORTUNITY) != 0 &&
        fixedDemandLedgerConserved(mutation.ledgerAfter);
}

bool sealFixedPriorRetirementProof(
        FixedSubmittedRetirement* retirement) noexcept {
    if (!retirement ||
        retirement->state != FixedRetirementState::RETIRED ||
        !fixedAppliedBufferRefValid(retirement->appliedBufferRef) ||
        retirement->retirementSequence == 0 ||
        retirement->targetAuthorityRawSequence == 0 ||
        retirement->targetPhysicalCallbackSequence == 0 ||
        retirement->plannedTargetFrame <= 0 ||
        retirement->originalTargetFrame <= 0 ||
        retirement->targetReachedNanos <= 0 ||
        retirement->retirementCompleteNanos <
            retirement->targetReachedNanos ||
        retirement->immutableProof.structSize != 0) {
        return false;
    }
    SwappyFixedPriorRetirementProofV1 proof{};
    proof.structSize = sizeof(proof);
    proof.version = SWAPPY_FIXED_PRIOR_RETIREMENT_PROOF_V1_VERSION;
    proof.hasPrior = 1;
    proof.predecessor = retirement->appliedBufferRef;
    proof.retirementSequence = retirement->retirementSequence;
    proof.targetAuthorityRawSequence =
        retirement->targetAuthorityRawSequence;
    proof.targetPhysicalCallbackSequence =
        retirement->targetPhysicalCallbackSequence;
    proof.plannedTargetFrame = retirement->plannedTargetFrame;
    proof.originalTargetFrame = retirement->originalTargetFrame;
    proof.targetReachedNanos = retirement->targetReachedNanos;
    proof.retirementCompleteNanos = retirement->retirementCompleteNanos;
    proof.proofCommittedNanos = std::max(
        retirement->retirementCompleteNanos, monotonicNowNanos());
    proof.targetWaitCount = retirement->targetWaitCount;
    proof.targetRebaseCount = retirement->targetRebaseCount;
    // Commit the exactly-once publication obligation with the immutable
    // retirement proof. Successor admission never waits for renderer callback
    // delivery, but the internal terminal publication is already owned.
    retirement->retirementCallbackPublishCount = 1;
    proof.retirementCallbackPublishCount = 1;
    proof.state = SWAPPY_FIXED_RETIREMENT_RETIRED;
    proof.fatalReason = 0;
    if (!fixedPriorRetirementProofValid(proof)) return false;
    retirement->immutableProof = proof;
    return true;
}

}  // namespace

std::int64_t SwappyCommon::externalClaimClockNowNanos() noexcept {
    return monotonicNowNanos();
}

// NB These are only needed for C++14
constexpr nanoseconds SwappyCommon::FrameDuration::MAX_DURATION;
constexpr nanoseconds SwappyCommon::FRAME_MARGIN;
constexpr nanoseconds SwappyCommon::DURATION_ROUNDING_MARGIN;
constexpr nanoseconds SwappyCommon::REFRESH_RATE_MARGIN;
constexpr int SwappyCommon::NON_PIPELINE_PERCENT;
constexpr int SwappyCommon::FRAME_DROP_THRESHOLD;
constexpr std::chrono::nanoseconds
    SwappyCommon::FrameDurations::FRAME_DURATION_SAMPLE_SECONDS;

#if __ANDROID_API__ < 30
// Define ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_* to allow compilation on older
// versions
enum {
    /**
     * There are no inherent restrictions on the frame rate of this window.
     */
    ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT = 0,
    /**
     * This window is being used to display content with an inherently fixed
     * frame rate, e.g. a video that has a specific frame rate. When the system
     * selects a frame rate other than what the app requested, the app will need
     * to do pull down or use some other technique to adapt to the system's
     * frame rate. The user experience is likely to be worse (e.g. more frame
     * stuttering) than it would be if the system had chosen the app's requested
     * frame rate.
     */
    ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE = 1
};
#endif

bool SwappyCommonSettings::getFromApp(JNIEnv* env, jobject jactivity,
                                      SwappyCommonSettings* out) {
    if (out == nullptr) return false;

    SWAPPY_LOGI("Swappy version %d.%d", SWAPPY_MAJOR_VERSION,
                SWAPPY_MINOR_VERSION);

    out->sdkVersion = getSDKVersion(env);

    jclass activityClass = env->FindClass("android/app/NativeActivity");
    jclass windowManagerClass = env->FindClass("android/view/WindowManager");
    jclass displayClass = env->FindClass("android/view/Display");

    jmethodID getWindowManager = env->GetMethodID(
        activityClass, "getWindowManager", "()Landroid/view/WindowManager;");

    jmethodID getDefaultDisplay = env->GetMethodID(
        windowManagerClass, "getDefaultDisplay", "()Landroid/view/Display;");

    jobject wm = env->CallObjectMethod(jactivity, getWindowManager);
    jobject display = env->CallObjectMethod(wm, getDefaultDisplay);

    jmethodID getRefreshRate =
        env->GetMethodID(displayClass, "getRefreshRate", "()F");

    const float refreshRateHz = env->CallFloatMethod(display, getRefreshRate);

    jmethodID getAppVsyncOffsetNanos =
        env->GetMethodID(displayClass, "getAppVsyncOffsetNanos", "()J");

    // getAppVsyncOffsetNanos was only added in API 21.
    // Return gracefully if this device doesn't support it.
    if (getAppVsyncOffsetNanos == 0 || env->ExceptionOccurred()) {
        SWAPPY_LOGE("Error while getting method: getAppVsyncOffsetNanos");
        env->ExceptionClear();
        return false;
    }
    const long appVsyncOffsetNanos =
        env->CallLongMethod(display, getAppVsyncOffsetNanos);

    jmethodID getPresentationDeadlineNanos =
        env->GetMethodID(displayClass, "getPresentationDeadlineNanos", "()J");

    if (getPresentationDeadlineNanos == 0 || env->ExceptionOccurred()) {
        SWAPPY_LOGE("Error while getting method: getPresentationDeadlineNanos");
        return false;
    }

    const long vsyncPresentationDeadlineNanos =
        env->CallLongMethod(display, getPresentationDeadlineNanos);

    const long ONE_MS_IN_NS = 1000 * 1000;
    const long ONE_S_IN_NS = ONE_MS_IN_NS * 1000;

    const long vsyncPeriodNanos =
        static_cast<long>(ONE_S_IN_NS / refreshRateHz);
    const long sfVsyncOffsetNanos =
        vsyncPeriodNanos - (vsyncPresentationDeadlineNanos - ONE_MS_IN_NS);

    using std::chrono::nanoseconds;
    out->refreshPeriod = nanoseconds(vsyncPeriodNanos);
    out->appVsyncOffset = nanoseconds(appVsyncOffsetNanos);
    out->sfVsyncOffset = nanoseconds(sfVsyncOffsetNanos);
    const long frameTimelineDeadlineNanos =
        fixedFrameTimelineDeadlineFromDisplay(
            vsyncPresentationDeadlineNanos);
    if (frameTimelineDeadlineNanos <= 0) return false;
    out->presentationDeadline = nanoseconds(frameTimelineDeadlineNanos);

    return true;
}

SwappyCommon::SwappyCommon(JNIEnv* env, jobject jactivity)
    : mJactivity(env->NewGlobalRef(jactivity)),
      mMeasuredSwapDuration(nanoseconds(0)),
      mAutoSwapInterval(1),
      mValid(false) {
    mLibAndroid = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (mLibAndroid == nullptr) {
        SWAPPY_LOGE("FATAL: cannot open libandroid.so: %s", strerror(errno));
        return;
    }

    if (!SwappyCommonSettings::getFromApp(env, mJactivity, &mCommonSettings))
        return;

    env->GetJavaVM(&mJVM);

    if (isDeviceUnsupported()) {
        SWAPPY_LOGE("Device is unsupported");
        return;
    }

    if (!SwappyDisplayManager::useSwappyDisplayManager(
            mCommonSettings.sdkVersion)) {
        mANativeWindow_setFrameRate =
            reinterpret_cast<PFN_ANativeWindow_setFrameRate>(
                dlsym(mLibAndroid, "ANativeWindow_setFrameRate"));
    }

    mChoreographerFilter = std::make_unique<ChoreographerFilter>(
        mCommonSettings.refreshPeriod,
        mCommonSettings.sfVsyncOffset - mCommonSettings.appVsyncOffset,
        [this](const ChoreographerFrameData& frame) {
            return wakeClient(frame);
        });

    mChoreographerThread = ChoreographerThread::createChoreographerThread(
        ChoreographerThread::Type::Swappy, mJVM, jactivity,
        [this](const ChoreographerFrameData& frame) {
            mChoreographerFilter->onChoreographer(frame);
        },
        [this] { onRefreshRateChanged(); }, mCommonSettings.sdkVersion);
    if (!mChoreographerThread->isInitialized()) {
        SWAPPY_LOGE("failed to initialize ChoreographerThread");
        return;
    }
    // Prime raw frame authority during attach work so the first causal swap
    // never has to block merely to obtain its first Choreographer timestamp.
    mChoreographerThread->postFrameCallbacks();
    if (USE_DISPLAY_MANAGER &&
        SwappyDisplayManager::usesMinSdkOrLater(mCommonSettings.sdkVersion)) {
        mDisplayManager =
            std::make_unique<SwappyDisplayManager>(mJVM, jactivity);

        if (!mDisplayManager->isInitialized()) {
            mDisplayManager = nullptr;
            SWAPPY_LOGE("failed to initialize DisplayManager");
            return;
        }
    }

    Settings::getInstance()->addListener([this]() { onSettingsChanged(); });
    Settings::getInstance()->setDisplayTimings({mCommonSettings.refreshPeriod,
                                                mCommonSettings.appVsyncOffset,
                                                mCommonSettings.sfVsyncOffset});

    mInitialRefreshPeriod = mCommonSettings.refreshPeriod;
    SWAPPY_LOGI(
        "Initialized Swappy with vsyncPeriod=%lld, appOffset=%lld, "
        "sfOffset=%lld, presentationDeadline=%lld",
        (long long)mCommonSettings.refreshPeriod.count(),
        (long long)mCommonSettings.appVsyncOffset.count(),
        (long long)mCommonSettings.sfVsyncOffset.count(),
        (long long)mCommonSettings.presentationDeadline.count());
    mValid = true;
}

// Used by tests
SwappyCommon::SwappyCommon(const SwappyCommonSettings& settings)
    : mJactivity(nullptr),
      mCommonSettings(settings),
      mMeasuredSwapDuration(nanoseconds(0)),
      mAutoSwapInterval(1),
      mValid(true) {
    mChoreographerFilter = std::make_unique<ChoreographerFilter>(
        mCommonSettings.refreshPeriod,
        mCommonSettings.sfVsyncOffset - mCommonSettings.appVsyncOffset,
        [this](const ChoreographerFrameData& frame) {
            return wakeClient(frame);
        });
    mUsingExternalChoreographer = true;
    mChoreographerThread = ChoreographerThread::createChoreographerThread(
        ChoreographerThread::Type::App, nullptr, nullptr,
        [this](const ChoreographerFrameData& frame) {
            mChoreographerFilter->onChoreographer(frame);
        },
        [] {}, mCommonSettings.sdkVersion);

    Settings::getInstance()->addListener([this]() { onSettingsChanged(); });
    Settings::getInstance()->setDisplayTimings({mCommonSettings.refreshPeriod,
                                                mCommonSettings.appVsyncOffset,
                                                mCommonSettings.sfVsyncOffset});

    mInitialRefreshPeriod = mCommonSettings.refreshPeriod;
    SWAPPY_LOGI(
        "Initialized Swappy with vsyncPeriod=%lld, appOffset=%lld, "
        "sfOffset=%lld, presentationDeadline=%lld",
        (long long)mCommonSettings.refreshPeriod.count(),
        (long long)mCommonSettings.appVsyncOffset.count(),
        (long long)mCommonSettings.sfVsyncOffset.count(),
        (long long)mCommonSettings.presentationDeadline.count());
}

SwappyCommon::~SwappyCommon() {
    // Remove the settings' listeners before destroying Choreographer objects
    // because the listeners may contain references to the objects
    Settings::getInstance()->removeAllListeners();
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        mFixedLifecycleClosing = true;
    }
    if (mChoreographerThread) {
        mChoreographerThread->cancelFixedFrameDemandForNtk(
            FIXED_DEMAND_RETIREMENT | FIXED_DEMAND_OPPORTUNITY);
    }
    // destroy all remaining threads before the other members of this class
    mChoreographerThread.reset();
    mChoreographerFilter.reset();

    Settings::reset();

    if (mJactivity != nullptr) {
        JNIEnv* env;
        mJVM->AttachCurrentThread(&env, nullptr);

        env->DeleteGlobalRef(mJactivity);
    }
}

void SwappyCommon::onRefreshRateChanged() {
    JNIEnv* env;
    mJVM->AttachCurrentThread(&env, nullptr);

    SWAPPY_LOGV("onRefreshRateChanged");

    SwappyCommonSettings settings;
    if (!SwappyCommonSettings::getFromApp(env, mJactivity, &settings)) {
        SWAPPY_LOGE("failed to query display timings");
        return;
    }

    {
        std::lock_guard<std::mutex> lock(mMutex);
        mCommonSettings.refreshPeriod = settings.refreshPeriod;
        mCommonSettings.appVsyncOffset = settings.appVsyncOffset;
        mCommonSettings.sfVsyncOffset = settings.sfVsyncOffset;
        mCommonSettings.presentationDeadline = settings.presentationDeadline;
        if (mFixedNonPipelineMode &&
            !validateFixedConfigurationLocked(mSwapDuration)) {
            mFixedPhaseConfigurationValid = false;
            mFatalPacingError.store(true, std::memory_order_release);
        }
    }

    Settings::getInstance()->setDisplayTimings({settings.refreshPeriod,
                                                settings.appVsyncOffset,
                                                settings.sfVsyncOffset});
    SWAPPY_LOGV("onRefreshRateChanged: refresh rate: %.0fHz",
                1e9f / settings.refreshPeriod.count());
}

nanoseconds SwappyCommon::wakeClient(const ChoreographerFrameData& frame) {
    onFixedChoreographerAuthority(frame);
    return mMeasuredSwapDuration;
}

FixedCandidateCaptureResult
SwappyCommon::captureLatestFixedRawCandidateLocked(
    bool carriedIntoReservation,
    const FixedPhasePlanInput& configuration,
    std::int64_t captureNanos,
    SwappyFixedWakeNotice* candidateNotice) {
    if (candidateNotice) *candidateNotice = {};
    if (!mFixedPreparedFrame.has_value() || mFixedLifecycleClosing) {
        return FixedCandidateCaptureResult::NO_RAW;
    }
    const FixedPreparedFrameIdentity& prepared = *mFixedPreparedFrame;
    if (prepared.state == FixedProducerState::TOKEN_ISSUED ||
        prepared.state == FixedProducerState::QUEUEING ||
        prepared.state == FixedProducerState::SUBMITTED ||
        prepared.state == FixedProducerState::FATAL ||
        mFixedAdmissionToken.has_value()) {
        return FixedCandidateCaptureResult::NO_RAW;
    }
    if (captureNanos <= 0 || captureNanos < prepared.reservationNanos) {
        mFixedPreparedFrame->state = FixedProducerState::FATAL;
        mFatalPacingError.store(true, std::memory_order_release);
        return FixedCandidateCaptureResult::FATAL;
    }
    const RawFixedFrameAuthority raw = mRawFixedFrameAuthority;
    if (raw.sequence == 0 ||
        raw.sequence <= mFixedLastDisposedRawSequence) {
        return FixedCandidateCaptureResult::NO_RAW;
    }
    if ((mFixedAvailableCandidate.has_value() &&
         raw.sequence <= mFixedAvailableCandidate->raw.sequence) ||
        (mFixedClaimedCandidate.has_value() &&
         raw.sequence <= mFixedClaimedCandidate->raw.sequence)) {
        return FixedCandidateCaptureResult::NO_RAW;
    }

    FixedPhasePlanInput phaseInput = configuration;
    phaseInput.acceptedFrameTimeNanos = raw.frameTimeNanos;
    phaseInput.acceptedFrameIndex = raw.frameIndex;
    phaseInput.decisionNanos = captureNanos;
    const FixedPhaseOpportunityAdmission admission =
        classifyFixedPhaseOpportunity(phaseInput);
    if (admission ==
        FixedPhaseOpportunityAdmission::FATAL_INVALID_GEOMETRY) {
        mFixedPreparedFrame->state = FixedProducerState::FATAL;
        mFatalPacingError.store(true, std::memory_order_release);
        return FixedCandidateCaptureResult::FATAL;
    }
    if (admission ==
        FixedPhaseOpportunityAdmission::SLOT_CLOSED_NO_ATTEMPT) {
        mFixedLastDisposedRawSequence = std::max(
            mFixedLastDisposedRawSequence, raw.sequence);
        ++mFixedClosedOpportunityCount;
        return FixedCandidateCaptureResult::RAW_CLOSED;
    }
    if (!candidateNotice || raw.callbackReceiptNanos <= 0 ||
        captureNanos < raw.callbackReceiptNanos) {
        mFixedPreparedFrame->state = FixedProducerState::FATAL;
        mFatalPacingError.store(true, std::memory_order_release);
        return FixedCandidateCaptureResult::FATAL;
    }

    FixedRawCandidate candidate{};
    candidate.candidateSequence = ++mFixedCandidateSequence;
    candidate.reservationSequence = prepared.reservationSequence;
    candidate.workGeneration = prepared.workGeneration;
    candidate.raw = raw;
    candidate.capturedNanos = captureNanos;
    candidate.carriedIntoReservation = carriedIntoReservation ||
        raw.callbackReceiptNanos < prepared.reservationNanos;

    SwappyFixedWakeNotice notice{};
    notice.structSize = sizeof(SwappyFixedWakeNotice);
    notice.version = SWAPPY_FIXED_WAKE_NOTICE_VERSION;
    notice.noticeSequence = ++mFixedWakeNoticeSequence;
    notice.workGeneration = candidate.workGeneration;
    notice.reservationSequence = candidate.reservationSequence;
    notice.candidateSequence = candidate.candidateSequence;
    notice.wakeReason = SWAPPY_FIXED_WAKE_CANDIDATE_AVAILABLE;
    notice.physicalReceiptNanos = raw.callbackReceiptNanos;
    notice.candidateCaptureNanos = candidate.capturedNanos;
    if (mFixedSubmittedRetirement.has_value()) {
        const FixedSubmittedRetirement& retired =
            *mFixedSubmittedRetirement;
        notice.priorRetirementSequence = retired.retirementSequence;
        notice.retirementStageNanos = retired.retirementStageNanos;
        notice.demandMutationCompleteNanos =
            retired.demandMutationCompleteNanos;
        notice.terminalVisibleNanos = retired.terminalVisibleNanos;
    }
    notice.wakeDispatchNanos = monotonicNowNanos();
    if (notice.wakeDispatchNanos < notice.candidateCaptureNanos) {
        mFixedPreparedFrame->state = FixedProducerState::FATAL;
        mFatalPacingError.store(true, std::memory_order_release);
        return FixedCandidateCaptureResult::FATAL;
    }
    candidate.wakeNotice =
        std::make_shared<SwappyFixedWakeNotice>(notice);
    if (mFixedAvailableCandidate.has_value()) {
        ++mFixedSupersededBeforeClaimCount;
    }
    mFixedAvailableCandidate = std::move(candidate);
    if (mFixedPreparedFrame->state ==
            FixedProducerState::GPU_READY_NO_CANDIDATE) {
        if (!claimAvailableFixedRawCandidateLocked(
                prepared.workGeneration, captureNanos)) {
            mFixedPreparedFrame->state = FixedProducerState::FATAL;
            mFatalPacingError.store(true, std::memory_order_release);
            return FixedCandidateCaptureResult::FATAL;
        }
        notice.candidateClaimNanos =
            mFixedClaimedCandidate->claimedNanos;
    }
    *candidateNotice = notice;
    return FixedCandidateCaptureResult::CAPTURED;
}

bool SwappyCommon::claimAvailableFixedRawCandidateLocked(
    std::uint64_t workGeneration, std::int64_t claimNanos) {
    if (!mFixedPreparedFrame.has_value() ||
        mFixedPreparedFrame->workGeneration != workGeneration ||
        !mFixedAvailableCandidate.has_value() ||
        mFixedClaimedCandidate.has_value() || claimNanos <= 0) {
        return false;
    }
    FixedRawCandidate candidate = std::move(*mFixedAvailableCandidate);
    mFixedAvailableCandidate.reset();
    if (candidate.workGeneration != workGeneration ||
        candidate.reservationSequence !=
            mFixedPreparedFrame->reservationSequence ||
        candidate.raw.sequence <= mFixedLastDisposedRawSequence) {
        return false;
    }
    candidate.state = FixedRawCandidateState::CLAIMED;
    candidate.claimedNanos = claimNanos;
    if (candidate.wakeNotice) {
        candidate.wakeNotice->candidateClaimNanos = claimNanos;
    }
    mFixedClaimedCandidate = std::move(candidate);
    mFixedPreparedFrame->state =
        FixedProducerState::GPU_READY_CLAIMED;
    return true;
}

bool SwappyCommon::publishClaimedFixedOpportunityIfJoinOpenLocked(
    std::int64_t publishNanos, SwappyFixedWakeNotice* joinNotice,
    bool* callbackDispatchRequired) {
    if (joinNotice) *joinNotice = {};
    if (callbackDispatchRequired) *callbackDispatchRequired = false;
    if (!mFixedPreparedFrame.has_value() ||
        !mFixedClaimedCandidate.has_value() || publishNanos <= 0) {
        return false;
    }
    SwappyFixedLatchObservationV1 priorLatchObservation{};
    std::uint32_t priorLatchGateRequired = 0;
    std::uint32_t priorLatchGateUsed = 0;
    std::uint32_t priorLatchWaitCount = 0;
    if (mFixedSubmittedRetirement.has_value()) {
        const FixedSubmittedRetirement& prior = *mFixedSubmittedRetirement;
        if (prior.state == FixedRetirementState::FATAL) return false;
        if (prior.state != FixedRetirementState::RETIRED ||
            !prior.terminalPublicationComplete) {
            mFixedPreparedFrame->state = FixedProducerState::JOIN_WAITING;
            return false;
        }
        priorLatchGateRequired = 1;
        if (!mFixedObservedPriorLatchSnapshot.has_value()) {
            mFixedPreparedFrame->priorLatchBlocked = true;
            mFixedPreparedFrame->state =
                FixedProducerState::JOIN_WAITING_PRIOR_PHYSICAL;
            return false;
        }
        priorLatchObservation = *mFixedObservedPriorLatchSnapshot;
        if (!fixedLatchObservationValid(priorLatchObservation) ||
            !fixedFrameIdentityExact(
                priorLatchObservation.identity,
                prior.appliedBufferRef.identity)) {
            mFixedPreparedFrame->state = FixedProducerState::FATAL;
            mFatalPacingError.store(true, std::memory_order_release);
            return false;
        }
        priorLatchGateUsed = 1;
        priorLatchWaitCount =
            mFixedPreparedFrame->priorLatchBlocked ? 1U : 0U;
    }
    const FixedRawCandidate& claimed = *mFixedClaimedCandidate;
    if (mFixedPublishedOpportunity.has_value() &&
        mFixedPublishedOpportunity->candidateSequence ==
            claimed.candidateSequence) {
        if (joinNotice) *joinNotice =
            mFixedPublishedOpportunity->wakeNotice;
        return true;
    }
    FixedPublishedOpportunity opportunity{};
    opportunity.opportunitySequence = ++mFixedOpportunitySequence;
    opportunity.candidateSequence = claimed.candidateSequence;
    opportunity.reservationSequence = claimed.reservationSequence;
    opportunity.workGeneration = claimed.workGeneration;
    opportunity.raw = claimed.raw;
    opportunity.publishNanos = publishNanos;
    opportunity.priorLatchObservation = priorLatchObservation;
    opportunity.priorLatchGateRequired = priorLatchGateRequired;
    opportunity.priorLatchGateUsed = priorLatchGateUsed;
    opportunity.priorLatchWaitCount = priorLatchWaitCount;
    SwappyFixedWakeNotice notice{};
    notice.structSize = sizeof(SwappyFixedWakeNotice);
    notice.version = SWAPPY_FIXED_WAKE_NOTICE_VERSION;
    notice.noticeSequence = ++mFixedWakeNoticeSequence;
    notice.workGeneration = claimed.workGeneration;
    notice.reservationSequence = claimed.reservationSequence;
    notice.opportunitySequence = opportunity.opportunitySequence;
    notice.candidateSequence = claimed.candidateSequence;
    notice.wakeReason = SWAPPY_FIXED_WAKE_JOIN_OPEN;
    notice.physicalReceiptNanos = claimed.raw.callbackReceiptNanos;
    notice.candidateCaptureNanos = claimed.capturedNanos;
    notice.candidateClaimNanos = claimed.claimedNanos;
    notice.opportunityPublishNanos = publishNanos;
    if (mFixedSubmittedRetirement.has_value()) {
        const FixedSubmittedRetirement& prior = *mFixedSubmittedRetirement;
        notice.priorRetirementSequence = prior.retirementSequence;
        notice.retirementStageNanos = prior.retirementStageNanos;
        notice.demandMutationCompleteNanos =
            prior.demandMutationCompleteNanos;
        notice.terminalVisibleNanos = prior.terminalVisibleNanos;
    }
    notice.wakeDispatchNanos = monotonicNowNanos();
    opportunity.wakeNotice = notice;
    mFixedPublishedOpportunity = opportunity;
    mFixedPreparedFrame->state = FixedProducerState::JOIN_WAITING;
    if (joinNotice) *joinNotice = notice;
    if (callbackDispatchRequired) *callbackDispatchRequired = true;
    return true;
}

ClosedOpportunityResult
SwappyCommon::disposeClosedClaimAndPromoteShadowLocked(
    const FixedOpportunityIdentity& expected,
    std::int64_t decisionNanos) {
    ClosedOpportunityResult result{};
    if (!mFixedPreparedFrame.has_value() ||
        mFixedPreparedFrame->workGeneration != expected.workGeneration ||
        mFixedPreparedFrame->reservationSequence !=
            expected.reservationSequence ||
        !mFixedPreparedFrame->commitInFlight ||
        !mFixedClaimedCandidate.has_value() ||
        !mFixedPublishedOpportunity.has_value() || decisionNanos <= 0 ||
        mFixedClaimedCandidate->candidateSequence !=
            expected.candidateSequence ||
        mFixedPublishedOpportunity->workGeneration !=
            expected.workGeneration ||
        mFixedPublishedOpportunity->reservationSequence !=
            expected.reservationSequence ||
        mFixedPublishedOpportunity->opportunitySequence !=
            expected.opportunitySequence ||
        mFixedPublishedOpportunity->candidateSequence !=
            expected.candidateSequence ||
        mFixedPublishedOpportunity->wakeNotice.noticeSequence !=
            expected.noticeSequence) {
        return result;
    }
    result.exact = true;
    mFixedLastDisposedRawSequence = std::max(
        mFixedLastDisposedRawSequence,
        mFixedClaimedCandidate->raw.sequence);
    ++mFixedClosedOpportunityCount;
    mFixedClaimedCandidate.reset();
    mFixedPublishedOpportunity.reset();
    mFixedPreparedFrame->commitInFlight = false;
    if (!mFixedAvailableCandidate.has_value() ||
        mFixedAvailableCandidate->raw.sequence <=
            mFixedLastDisposedRawSequence) {
        mFixedPreparedFrame->state =
            FixedProducerState::GPU_READY_NO_CANDIDATE;
        return result;
    }
    ++mFixedShadowPromotionCount;
    if (!claimAvailableFixedRawCandidateLocked(
            expected.workGeneration, decisionNanos)) {
        result.exact = false;
        return result;
    }
    SwappyFixedWakeNotice next{};
    bool callbackDispatchRequired = false;
    result.nextPublished =
        publishClaimedFixedOpportunityIfJoinOpenLocked(
            monotonicNowNanos(), &next, &callbackDispatchRequired) &&
        callbackDispatchRequired &&
        next.opportunitySequence > expected.opportunitySequence;
    if (result.nextPublished) result.nextNotice = next;
    return result;
}

FixedPhaseAdmissionStatus SwappyCommon::finishClosedOpportunityForNtk(
        const FixedOpportunityIdentity& expected,
        std::int64_t decisionNanos) {
    ClosedOpportunityResult disposition{};
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        disposition = disposeClosedClaimAndPromoteShadowLocked(
            expected, decisionNanos);
    }
    if (!disposition.exact) {
        failFixedPhase(FixedPhaseFatalReason::ADMISSION_IDENTITY_MISMATCH);
        return FixedPhaseAdmissionStatus::FATAL;
    }
    if (disposition.nextPublished) {
        if (!disposition.nextNotice.has_value()) {
            failFixedPhase(FixedPhaseFatalReason::CONSERVATION_FAILURE);
            return FixedPhaseAdmissionStatus::FATAL;
        }
        fixedPhaseOpportunityCallbacks(&*disposition.nextNotice);
        return mFatalPacingError.load(std::memory_order_acquire)
            ? FixedPhaseAdmissionStatus::FATAL
            : FixedPhaseAdmissionStatus::SLOT_CLOSED_WAITING_NEXT;
    }
    if (mUsingExternalChoreographer || !mChoreographerThread) {
        failFixedPhase(FixedPhaseFatalReason::NO_RAW_FRAME_AUTHORITY);
        return FixedPhaseAdmissionStatus::FATAL;
    }
    const FixedDemandMutationResult mutation =
        mChoreographerThread->requestFixedFrameCallbackForNtk(
            FIXED_DEMAND_OPPORTUNITY);
    disposition.nextDemandOutstanding =
        fixedDemandMutationOwnsOpportunity(mutation);
    if (!disposition.nextDemandOutstanding) {
        failFixedPhase(FixedPhaseFatalReason::CONSERVATION_FAILURE);
        return FixedPhaseAdmissionStatus::FATAL;
    }
    return FixedPhaseAdmissionStatus::SLOT_CLOSED_WAITING_NEXT;
}

std::uint8_t SwappyCommon::onFixedChoreographerAuthority(
    const ChoreographerFrameData& frame) {
    FixedPhasePlanInput bindConfiguration{};
    {
        std::lock_guard<std::mutex> lock(mMutex);
        bindConfiguration.refreshPeriodNanos =
            mCommonSettings.refreshPeriod.count();
        bindConfiguration.appVsyncOffsetNanos =
            mCommonSettings.appVsyncOffset.count();
        bindConfiguration.presentationDeadlineNanos =
            mCommonSettings.presentationDeadline.count();
    }
    std::uint8_t satisfiedMask = FIXED_DEMAND_NONE;
    bool requestNextOpportunity = false;
    bool retirementCountExact = false;
    std::optional<FixedSubmittedRetirement> terminalSidecar;
    std::optional<SwappyFixedWakeNotice> joinNotice;
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        if (frame.frameTimeNanos <= 0 || frame.frameIndex <= mCurrentFrame) {
            return satisfiedMask;
        }
        mCurrentFrame = frame.frameIndex;
        mAcceptedFrameTimeNanos = frame.frameTimeNanos;
        mAcceptedFrameIndex = frame.frameIndex;
        mFrameTimelineVsyncId = frame.frameTimelineVsyncId;
        mAcceptedFrameHasTimeline = frame.hasFrameTimeline;
        mTimelinePresentationDeadlineNanos = frame.hasFrameTimeline
            ? frame.expectedPresentationTimeNanos -
                  frame.frameTimelineDeadlineNanos : 0;
        ++mRawFixedFrameAuthority.sequence;
        mRawFixedFrameAuthority.physicalCallbackSequence =
            frame.physicalCallbackSequence;
        mRawFixedFrameAuthority.frameTimeNanos = frame.frameTimeNanos;
        mRawFixedFrameAuthority.frameIndex = frame.frameIndex;
        mRawFixedFrameAuthority.frameTimelineVsyncId =
            frame.frameTimelineVsyncId;
        mRawFixedFrameAuthority.timelineExpectedPresentationNanos =
            frame.expectedPresentationTimeNanos;
        mRawFixedFrameAuthority.timelinePresentationDeadlineNanos =
            mTimelinePresentationDeadlineNanos;
        mRawFixedFrameAuthority.callbackReceiptNanos =
            frame.physicalCallbackReceiptNanos;
        mRawFixedFrameAuthority.hasTimeline = frame.hasFrameTimeline;
        mRawFixedFrameAuthority.frameTimelines = frame.frameTimelines;
        mCurrentFrameTimestamp = std::chrono::steady_clock::now() +
            mMeasuredSwapDuration.load() + 1ms;
        mSfToVsyncDelay = frame.sfToVsyncDelay;

        SwappyFixedWakeNotice captured{};
        const FixedCandidateCaptureResult capture =
            captureLatestFixedRawCandidateLocked(
                false, bindConfiguration, monotonicNowNanos(), &captured);
        if (capture == FixedCandidateCaptureResult::CAPTURED) {
            // Candidate capture is internal telemetry, never commit authority.
            if (mFixedClaimedCandidate.has_value()) {
                SwappyFixedWakeNotice published{};
                bool callbackDispatchRequired = false;
                if (publishClaimedFixedOpportunityIfJoinOpenLocked(
                        monotonicNowNanos(), &published,
                        &callbackDispatchRequired) &&
                    callbackDispatchRequired) {
                    joinNotice = published;
                }
            }
        } else if (capture == FixedCandidateCaptureResult::RAW_CLOSED) {
            requestNextOpportunity = !mFixedClaimedCandidate.has_value() &&
                !mFixedAvailableCandidate.has_value();
        } else if (capture == FixedCandidateCaptureResult::FATAL) {
            mFatalPacingError.store(true, std::memory_order_release);
        }

        if (mFixedRefreshTicket.issued &&
            !mFixedRefreshTicket.delivered &&
            (frame.deliveredFixedDemandMask & FIXED_DEMAND_OPPORTUNITY)) {
            mFixedRefreshTicket.delivered = true;
            mFixedRefreshTicket.physicalCallbackSequence =
                frame.physicalCallbackSequence;
            mFixedRefreshTicket.capturedRawSequence =
                mRawFixedFrameAuthority.sequence;
            satisfiedMask = static_cast<std::uint8_t>(
                satisfiedMask | FIXED_DEMAND_OPPORTUNITY);
        }

        if (mFixedSubmittedRetirement.has_value()) {
            FixedSubmittedRetirement& submitted =
                *mFixedSubmittedRetirement;
            if (submitted.state == FixedRetirementState::WAIT_ARMED &&
                qualifiesFixedTargetAuthority(
                    submitted, mRawFixedFrameAuthority)) {
                submitted.state = FixedRetirementState::PUBLISHING;
                submitted.terminalPublicationComplete = false;
                submitted.targetAuthorityRawSequence =
                    mRawFixedFrameAuthority.sequence;
                submitted.targetPhysicalCallbackSequence =
                    frame.physicalCallbackSequence;
                submitted.targetFrameTimeNanos = frame.frameTimeNanos;
                submitted.targetFrameIndex = frame.frameIndex;
                submitted.targetAuthorityNanos =
                    frame.physicalCallbackReceiptNanos;
                submitted.targetReachedNanos =
                    frame.physicalCallbackReceiptNanos;
                submitted.retirementPublishNanos = monotonicNowNanos();
                submitted.retirementCompleteNanos =
                    submitted.retirementPublishNanos;
                submitted.retirementStageNanos =
                    submitted.retirementPublishNanos;
                terminalSidecar = submitted;
                retirementCountExact =
                    mFixedTargetRetiredCount + 1 <= mFixedSubmittedCount &&
                    mFixedSubmittedCount - (mFixedTargetRetiredCount + 1) <= 1;
            }
        }
        if ((frame.deliveredFixedDemandMask & FIXED_DEMAND_RETIREMENT) &&
            (!mFixedSubmittedRetirement.has_value() ||
             terminalSidecar.has_value())) {
            satisfiedMask = static_cast<std::uint8_t>(
                satisfiedMask | FIXED_DEMAND_RETIREMENT);
        }
    }

    FixedDemandMutationResult demandMutation{};
    if (!mUsingExternalChoreographer && mChoreographerThread &&
        frame.physicalCallbackSequence != 0) {
        demandMutation = mChoreographerThread->completeFixedFrameCallbackForNtk(
            frame.physicalCallbackSequence,
            frame.deliveredFixedDemandMask, satisfiedMask);
    } else {
        demandMutation.accepted =
            frame.deliveredFixedDemandMask == FIXED_DEMAND_NONE;
        demandMutation.ledgerAfter = mChoreographerThread
            ? mChoreographerThread->getFixedDemandLedgerForNtk()
            : FixedDemandLedgerSnapshot{};
    }

    if (terminalSidecar.has_value()) {
        const FixedDemandLedgerSnapshot& ledger = demandMutation.ledgerAfter;
        terminalSidecar->retirementDemandIssued = ledger.retirementIssued;
        terminalSidecar->retirementDemandSatisfied = ledger.retirementSatisfied;
        terminalSidecar->retirementDemandCancelled = ledger.retirementCancelled;
        terminalSidecar->demandMutationCompleteNanos =
            demandMutation.mutationCompleteNanos;
        terminalSidecar->terminalVisibleNanos = monotonicNowNanos();
        terminalSidecar->rendererWakePublishNanos =
            terminalSidecar->terminalVisibleNanos;
        const bool demandExact =
            terminalSidecar->retirementDemandIssued ==
                terminalSidecar->retirementDemandSatisfied +
                    terminalSidecar->retirementDemandCancelled;
        bool terminalExact = demandMutation.accepted && demandExact &&
            retirementCountExact &&
            terminalSidecar->targetAuthorityNanos >=
                terminalSidecar->postSwapNanos &&
            terminalSidecar->demandMutationCompleteNanos >=
                terminalSidecar->retirementStageNanos &&
            terminalSidecar->terminalVisibleNanos >=
                terminalSidecar->demandMutationCompleteNanos;
        terminalSidecar->state = terminalExact
            ? FixedRetirementState::RETIRED : FixedRetirementState::FATAL;
        if (terminalExact &&
            !sealFixedPriorRetirementProof(&*terminalSidecar)) {
            terminalExact = false;
            terminalSidecar->state = FixedRetirementState::FATAL;
        }
        SwappyFixedLatchObservationV1 observedPhysical{};
        const FixedLatchLookupResult physicalState =
            snapshotFixedLatchObservation(
                terminalSidecar->appliedBufferRef.identity,
                &observedPhysical);
        if (terminalExact &&
            physicalState != FixedLatchLookupResult::PENDING &&
            physicalState != FixedLatchLookupResult::OBSERVED) {
            terminalExact = false;
            terminalSidecar->state = FixedRetirementState::FATAL;
        }
        terminalSidecar->terminalPublicationComplete = true;
        terminalSidecar->fatalReason = terminalExact ? 0 :
            static_cast<std::int32_t>(
                FixedPhaseFatalReason::SUBMISSION_FAILED);
        {
            std::lock_guard<std::mutex> lock(mWaitingMutex);
            if (!mFixedSubmittedRetirement.has_value() ||
                mFixedSubmittedRetirement->workGeneration !=
                    terminalSidecar->workGeneration ||
                mFixedSubmittedRetirement->retirementSequence !=
                    terminalSidecar->retirementSequence ||
                mFixedSubmittedRetirement->state !=
                    FixedRetirementState::PUBLISHING) {
                terminalSidecar->state = FixedRetirementState::FATAL;
                terminalSidecar->fatalReason = static_cast<std::int32_t>(
                    FixedPhaseFatalReason::SUBMISSION_FAILED);
                mFatalPacingError.store(true, std::memory_order_release);
            }
            *mFixedSubmittedRetirement = *terminalSidecar;
            if (terminalSidecar->state == FixedRetirementState::RETIRED) {
                ++mFixedTargetRetiredCount;
                if (physicalState == FixedLatchLookupResult::OBSERVED) {
                    mFixedObservedPriorLatchSnapshot = observedPhysical;
                }
                SwappyFixedWakeNotice joined{};
                bool callbackDispatchRequired = false;
                if (publishClaimedFixedOpportunityIfJoinOpenLocked(
                        monotonicNowNanos(), &joined,
                        &callbackDispatchRequired) &&
                    callbackDispatchRequired) {
                    joinNotice = joined;
                }
            }
            mWaitingCondition.notify_all();
        }
    }

    if (!demandMutation.accepted &&
        frame.deliveredFixedDemandMask != FIXED_DEMAND_NONE) {
        mFatalPacingError.store(true, std::memory_order_release);
    }
    if (requestNextOpportunity && !mUsingExternalChoreographer &&
        mChoreographerThread) {
        const FixedDemandMutationResult mutation =
            mChoreographerThread->requestFixedFrameCallbackForNtk(
                FIXED_DEMAND_OPPORTUNITY);
        if (!fixedDemandMutationOwnsOpportunity(mutation)) {
            failFixedPhase(FixedPhaseFatalReason::CONSERVATION_FAILURE);
        }
    }
    if (joinNotice.has_value()) {
        fixedPhaseOpportunityCallbacks(&*joinNotice);
    }
    // JOIN authority is latency-critical. Retirement telemetry is a separate
    // evidence lane and must never overtake the already-immutable JOIN_OPEN.
    // Publish terminal evidence exactly once even if the renderer callback
    // fails closed while consuming the JOIN notice.
    if (terminalSidecar.has_value()) {
        finishFixedFrameStatistics(*terminalSidecar);
    }
    return satisfiedMask;
}

void SwappyCommon::onChoreographer(int64_t frameTimeNanos) {
    const std::int64_t physicalReceiptNanos = monotonicNowNanos();
    static std::atomic<std::uint64_t> externalPhysicalSequence{0};
    TRACE_CALL();
    if (!mUsingExternalChoreographer) {
        mUsingExternalChoreographer = true;
        mChoreographerThread = ChoreographerThread::createChoreographerThread(
            ChoreographerThread::Type::App, nullptr, nullptr,
            [this](const ChoreographerFrameData& frame) {
                mChoreographerFilter->onChoreographer(frame);
            },
            [this] { onRefreshRateChanged(); }, mCommonSettings.sdkVersion);
    }
    ChoreographerFrameData frame;
    frame.physicalCallbackReceiptNanos = physicalReceiptNanos;
    frame.physicalCallbackSequence =
        externalPhysicalSequence.fetch_add(1, std::memory_order_relaxed) + 1;
    frame.frameTimeNanos = frameTimeNanos;
    mChoreographerFilter->onChoreographer(frame);
}

bool SwappyCommon::planFixedPhaseForTesting(
    const SwappyFixedPhasePlanInput* input,
    SwappyFixedPhaseTelemetry* output) {
    if (!input || !output) return false;
    *output = {};
    output->schemaVersion = SWAPPY_FIXED_PHASE_TELEMETRY_VERSION;
    const FixedPhasePlanInput exactInput{
        input->refreshPeriodNanos,
        input->appVsyncOffsetNanos,
        input->presentationDeadlineNanos,
        input->acceptedFrameTimeNanos,
        input->acceptedFrameIndex,
        input->decisionNanos,
    };
    const FixedPhasePlan plan = planFixedNonPipelinePhase(exactInput);
    copyPlanToTelemetry(exactInput, plan, output);
    if (!plan.valid) {
        output->fatalReason =
            static_cast<std::int32_t>(FixedPhaseFatalReason::PLAN_REJECTED);
    }
    return plan.valid;
}

bool SwappyCommon::getFixedPhaseTelemetryForNtk(
    std::uint64_t workGeneration, SwappyFixedPhaseTelemetry* output) {
    if (!output || workGeneration == 0) return false;
    std::lock_guard<std::mutex> lock(mFixedPhaseTelemetryMutex);
    FixedGenerationTelemetryRecord* record =
        findFixedTelemetryLocked(workGeneration);
    if (!record || record->phase.sequence == 0 ||
        record->phase.workGeneration != workGeneration) {
        *output = {};
        return false;
    }
    *output = record->phase;
    return true;
}

SwappyCommon::FixedGenerationTelemetryRecord*
SwappyCommon::findFixedTelemetryLocked(std::uint64_t workGeneration) {
    if (workGeneration == 0) return nullptr;
    for (auto& record : mFixedTelemetryRing) {
        if (record.workGeneration == workGeneration) return &record;
    }
    return nullptr;
}

SwappyCommon::FixedGenerationTelemetryRecord&
SwappyCommon::beginFixedTelemetryLocked(std::uint64_t workGeneration) {
    for (std::size_t i = 0; i < mFixedTelemetryRing.size(); ++i) {
        if (mFixedTelemetryRing[i].workGeneration == workGeneration) {
            FixedGenerationTelemetryRecord& existing = mFixedTelemetryRing[i];
            existing = {};
            existing.workGeneration = workGeneration;
            mFixedActiveTelemetrySlot = i;
            mFixedActiveTelemetryValid = true;
            return existing;
        }
    }
    FixedGenerationTelemetryRecord& record =
        mFixedTelemetryRing[mFixedNextTelemetrySlot];
    record = {};
    record.workGeneration = workGeneration;
    mFixedActiveTelemetrySlot = mFixedNextTelemetrySlot;
    mFixedActiveTelemetryValid = true;
    mFixedNextTelemetrySlot =
        (mFixedNextTelemetrySlot + 1) % FIXED_TELEMETRY_RING_SIZE;
    return record;
}

SwappyCommon::FixedGenerationTelemetryRecord*
SwappyCommon::activeFixedTelemetryLocked() {
    return mFixedActiveTelemetryValid
        ? &mFixedTelemetryRing[mFixedActiveTelemetrySlot] : nullptr;
}


bool SwappyCommon::qualifiesFixedTargetAuthority(
    const FixedSubmittedRetirement& submitted,
    const RawFixedFrameAuthority& raw) {
    return raw.sequence != 0 && raw.physicalCallbackSequence != 0 &&
        raw.frameIndex >= submitted.plannedTargetFrame &&
        raw.callbackReceiptNanos >= submitted.postSwapNanos;
}


bool SwappyCommon::validateFixedConfigurationLocked(
    nanoseconds swapDuration) const {
    const std::int64_t period = mCommonSettings.refreshPeriod.count();
    const std::int64_t appOffset = mCommonSettings.appVsyncOffset.count();
    const std::int64_t deadline =
        mCommonSettings.presentationDeadline.count();
    if (std::llabs(period - kFixedNinetyHzPeriodNanos) >
            kFixedRefreshToleranceNanos ||
        std::llabs(swapDuration.count() - period) >
            kFixedRefreshToleranceNanos ||
        appOffset < 0 || appOffset >= period || deadline <= 0 ||
        deadline >= period) {
        return false;
    }
    const std::int64_t horizon = period + period / 2;
    const std::int64_t feasibility = period / 2;
    return deadline <= horizon - feasibility && mAutoSwapInterval == 1 &&
           mPipelineMode == PipelineMode::Off && mFramePacingEnabled &&
           mBlockingWaitEnabled && !mPresentationTimeNeeded;
}

bool SwappyCommon::isFixedPhaseConfigurationValidForNtk() {
    std::lock_guard<std::mutex> lock(mMutex);
    return mFixedNonPipelineMode && mFixedPhaseConfigurationValid &&
           validateFixedConfigurationLocked(mSwapDuration);
}

bool SwappyCommon::failFixedPhase(FixedPhaseFatalReason reason) {
    SWAPPY_LOGE("fixed phase fatal reason=%d", static_cast<int>(reason));
    {
        std::lock_guard<std::mutex> lock(mFixedPhaseTelemetryMutex);
        FixedGenerationTelemetryRecord* record = activeFixedTelemetryLocked();
        if (record) {
            if (record->phase.fatalReason == 0) {
                record->phase.fatalReason = static_cast<std::int32_t>(reason);
            }
            if (record->phase.sequence == 0) {
                record->phase.sequence = ++mFixedPhaseSequence;
            }
            record->phase.admissionStatus =
                static_cast<std::int32_t>(FixedPhaseAdmissionStatus::FATAL);
        }
    }
    mFatalPacingError.store(true, std::memory_order_release);
    return false;
}

FixedPhaseAdmissionStatus SwappyCommon::reserveFixedFrameForNtk(
    std::uint64_t workGeneration,
    FixedReservationReceipt* out) {
    if (out) *out = {};
    if (!out || workGeneration == 0) {
        failFixedPhase(FixedPhaseFatalReason::ADMISSION_IDENTITY_MISMATCH);
        return FixedPhaseAdmissionStatus::FATAL;
    }
    if (mFatalPacingError.load(std::memory_order_acquire)) {
        return FixedPhaseAdmissionStatus::FATAL;
    }

    bool configValid = false;
    FixedPhasePlanInput bindConfiguration{};
    {
        std::lock_guard<std::mutex> lock(mMutex);
        configValid = mFixedNonPipelineMode &&
            mFixedPhaseConfigurationValid &&
            validateFixedConfigurationLocked(mSwapDuration);
        bindConfiguration.refreshPeriodNanos =
            mCommonSettings.refreshPeriod.count();
        bindConfiguration.appVsyncOffsetNanos =
            mCommonSettings.appVsyncOffset.count();
        bindConfiguration.presentationDeadlineNanos =
            mCommonSettings.presentationDeadline.count();
    }
    if (!configValid) {
        failFixedPhase(FixedPhaseFatalReason::INVALID_CONFIGURATION);
        return FixedPhaseAdmissionStatus::FATAL;
    }

    bool identityInvalid = false;
    bool captureFatal = false;
    std::uint64_t reservationSequence = 0;
    std::int64_t reservationNanos = 0;
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        if (mFixedAdmissionToken.has_value()) {
            identityInvalid = true;
        } else if (mFixedPreparedFrame.has_value()) {
            if (mFixedPreparedFrame->workGeneration == workGeneration &&
                !mFixedPreparedFrame->commitInFlight) {
                out->workGeneration = workGeneration;
                out->reservationSequence =
                    mFixedPreparedFrame->reservationSequence;
                out->rawBaselineSequence =
                    mFixedPreparedFrame->rawBaselineSequence;
                out->reservationNanos =
                    mFixedPreparedFrame->reservationNanos;
                return FixedPhaseAdmissionStatus::ADMITTED;
            }
            identityInvalid = true;
        }
        if (!identityInvalid &&
            workGeneration <= mLastAdmittedWorkGeneration) {
            identityInvalid = true;
        } else if (!identityInvalid) {
            FixedPreparedFrameIdentity prepared{};
            prepared.workGeneration = workGeneration;
            prepared.reservationSequence = ++mFixedReservationSequence;
            prepared.reservationNanos = monotonicNowNanos();
            prepared.rawBaselineSequence =
                mFixedLastDisposedRawSequence;
            if (prepared.reservationNanos <= 0) {
                identityInvalid = true;
            } else {
                mFixedAvailableCandidate.reset();
                mFixedClaimedCandidate.reset();
                mFixedPublishedOpportunity.reset();
                mFixedRefreshTicket = {
                    .reservationSequence = prepared.reservationSequence,
                    .issued = true,
                };
                mFixedPreparedFrame = prepared;
                SwappyFixedWakeNotice ignoredCarryInNotice{};
                const FixedCandidateCaptureResult capture =
                    captureLatestFixedRawCandidateLocked(
                        true, bindConfiguration, prepared.reservationNanos,
                        &ignoredCarryInNotice);
                captureFatal =
                    capture == FixedCandidateCaptureResult::FATAL;
                reservationSequence = prepared.reservationSequence;
                reservationNanos = prepared.reservationNanos;
            }
            if (!mFixedPreparedFrame.has_value()) {
                mFixedPreparedFrame = prepared;
            }
            mFixedAdmissionPreSwapCommitted = false;
            out->workGeneration = workGeneration;
            out->reservationSequence = prepared.reservationSequence;
            out->rawBaselineSequence = prepared.rawBaselineSequence;
            out->reservationNanos = prepared.reservationNanos;
        }
    }
    if (identityInvalid) {
        failFixedPhase(FixedPhaseFatalReason::ADMISSION_IDENTITY_MISMATCH);
        return FixedPhaseAdmissionStatus::FATAL;
    }
    if (captureFatal) {
        failFixedPhase(FixedPhaseFatalReason::INVALID_CONFIGURATION);
        return FixedPhaseAdmissionStatus::FATAL;
    }
    {
        std::lock_guard<std::mutex> lock(mFixedPhaseTelemetryMutex);
        FixedGenerationTelemetryRecord& record =
            beginFixedTelemetryLocked(workGeneration);
        record.phase.schemaVersion = SWAPPY_FIXED_PHASE_TELEMETRY_VERSION;
        record.phase.workGeneration = workGeneration;
        record.phase.reservationSequence = reservationSequence;
        record.phase.reservationNanos = reservationNanos;
        record.phase.refreshIssued = 1;
    }
    if (mUsingExternalChoreographer || !mChoreographerThread) {
        failFixedPhase(FixedPhaseFatalReason::NO_RAW_FRAME_AUTHORITY);
        return FixedPhaseAdmissionStatus::FATAL;
    }
    const FixedDemandMutationResult refreshRequest =
        mChoreographerThread->requestFixedFrameCallbackForNtk(
            FIXED_DEMAND_OPPORTUNITY);
    if (!fixedDemandMutationOwnsOpportunity(refreshRequest)) {
        failFixedPhase(FixedPhaseFatalReason::CONSERVATION_FAILURE);
        return FixedPhaseAdmissionStatus::FATAL;
    }
    return FixedPhaseAdmissionStatus::ADMITTED;
}

bool SwappyCommon::markReservedExternalGpuReadyForNtk(
    std::uint64_t workGeneration,
    const SwappyFixedExternalTransportReady& transportReady) {
    if (workGeneration == 0 ||
        mFatalPacingError.load(std::memory_order_acquire) ||
        !fixedExternalTransportReadyValid(transportReady) ||
        transportReady.workGeneration != workGeneration) {
        return false;
    }
    bool identityInvalid = false;
    std::optional<SwappyFixedWakeNotice> joinNotice;
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        identityInvalid = !mFixedPreparedFrame.has_value() ||
            mFixedPreparedFrame->workGeneration != workGeneration ||
            mFixedPreparedFrame->state != FixedProducerState::RESERVED ||
            mFixedPreparedFrame->commitInFlight ||
            mFixedAdmissionToken.has_value();
        if (!identityInvalid) {
            mFixedPreparedFrame->gpuProofGeneration = workGeneration;
            mFixedPreparedFrame->gpuProofReady = true;
            mFixedPreparedFrame->transportReady = transportReady;
            if (mFixedAvailableCandidate.has_value()) {
                if (!claimAvailableFixedRawCandidateLocked(
                        workGeneration, monotonicNowNanos())) {
                    identityInvalid = true;
                } else {
                    SwappyFixedWakeNotice published{};
                    bool callbackDispatchRequired = false;
                    if (publishClaimedFixedOpportunityIfJoinOpenLocked(
                            monotonicNowNanos(), &published,
                            &callbackDispatchRequired) &&
                        callbackDispatchRequired) {
                        joinNotice = published;
                    }
                }
            } else {
                mFixedPreparedFrame->state =
                    FixedProducerState::GPU_READY_NO_CANDIDATE;
            }
        }
    }
    if (identityInvalid) {
        return failFixedPhase(
            FixedPhaseFatalReason::ADMISSION_IDENTITY_MISMATCH);
    }
    if (joinNotice.has_value()) {
        fixedPhaseOpportunityCallbacks(&*joinNotice);
    }
    return true;
}

bool SwappyCommon::registerFixedLatchExpectation(
        const SwappyFixedFrameIdentityV1& identity) {
    if (!fixedFrameIdentityValid(identity)) return false;
    std::lock_guard<std::mutex> lock(mFixedLatchObservationMutex);
    FixedLatchObservationRecord* reusable = nullptr;
    FixedLatchObservationRecord* oldestConsumed = nullptr;
    for (auto& record : mFixedLatchObservations) {
        if (record.state != FixedLatchRecordState::EMPTY &&
            fixedFrameIdentityExact(record.expected, identity)) {
            record.state = FixedLatchRecordState::FAILED;
            return false;
        }
        if (record.state == FixedLatchRecordState::EMPTY &&
            reusable == nullptr) {
            reusable = &record;
        } else if (
            record.state == FixedLatchRecordState::CONSUMED_BY_SUCCESSOR &&
                   (oldestConsumed == nullptr ||
                    record.registrationSequence <
                        oldestConsumed->registrationSequence)) {
            oldestConsumed = &record;
        }
    }
    // An observed proof remains owned by exactly one future successor.  Only
    // a proof already consumed by that successor may be recycled.
    if (reusable == nullptr) reusable = oldestConsumed;
    if (reusable == nullptr) return false;
    const std::uint64_t registrationSequence =
        mFixedLatchRegistrationSequence + 1;
    if (registrationSequence == 0) return false;
    mFixedLatchRegistrationSequence = registrationSequence;
    *reusable = {};
    reusable->state = FixedLatchRecordState::EXPECTED;
    reusable->expected = identity;
    reusable->registrationSequence = registrationSequence;
    return true;
}

SwappyCommon::FixedLatchLookupResult
SwappyCommon::snapshotFixedLatchObservation(
        const SwappyFixedFrameIdentityV1& identity,
        SwappyFixedLatchObservationV1* observation) {
    if (observation) *observation = {};
    if (!observation || !fixedFrameIdentityValid(identity)) {
        return FixedLatchLookupResult::MISSING;
    }
    std::lock_guard<std::mutex> lock(mFixedLatchObservationMutex);
    for (const auto& record : mFixedLatchObservations) {
        if (record.state == FixedLatchRecordState::EMPTY ||
            !fixedFrameIdentityExact(record.expected, identity)) {
            continue;
        }
        switch (record.state) {
            case FixedLatchRecordState::EXPECTED:
                return FixedLatchLookupResult::PENDING;
            case FixedLatchRecordState::OBSERVED:
                if (record.observationCount != 1 ||
                    !fixedLatchObservationValid(record.observation)) {
                    return FixedLatchLookupResult::FAILED;
                }
                *observation = record.observation;
                return FixedLatchLookupResult::OBSERVED;
            case FixedLatchRecordState::CONSUMED_BY_SUCCESSOR:
                return FixedLatchLookupResult::CONSUMED;
            case FixedLatchRecordState::FAILED:
                return FixedLatchLookupResult::FAILED;
            case FixedLatchRecordState::EMPTY:
                break;
        }
    }
    return FixedLatchLookupResult::MISSING;
}

bool SwappyCommon::consumeFixedLatchObservationForSuccessor(
        const SwappyFixedFrameIdentityV1& identity,
        const SwappyFixedLatchObservationV1& expectedObservation) {
    if (!fixedFrameIdentityValid(identity) ||
        !fixedLatchObservationValid(expectedObservation) ||
        !fixedFrameIdentityExact(expectedObservation.identity, identity)) {
        return false;
    }
    std::lock_guard<std::mutex> lock(mFixedLatchObservationMutex);
    for (auto& record : mFixedLatchObservations) {
        if (record.state == FixedLatchRecordState::EMPTY ||
            !fixedFrameIdentityExact(record.expected, identity)) {
            continue;
        }
        if (record.state != FixedLatchRecordState::OBSERVED ||
            record.observationCount != 1 ||
            !fixedLatchObservationExact(
                record.observation, expectedObservation)) {
            return false;
        }
        record.state = FixedLatchRecordState::CONSUMED_BY_SUCCESSOR;
        return true;
    }
    return false;
}

bool SwappyCommon::discardFixedLatchExpectation(
        const SwappyFixedFrameIdentityV1& identity) {
    if (!fixedFrameIdentityValid(identity)) return false;
    std::lock_guard<std::mutex> lock(mFixedLatchObservationMutex);
    for (auto& record : mFixedLatchObservations) {
        if (record.state == FixedLatchRecordState::EMPTY ||
            !fixedFrameIdentityExact(record.expected, identity)) {
            continue;
        }
        if (record.state != FixedLatchRecordState::EXPECTED ||
            record.observationCount != 0) {
            record.state = FixedLatchRecordState::FAILED;
            return false;
        }
        record = {};
        return true;
    }
    return false;
}

bool SwappyCommon::recordExternalLatchObservationForNtk(
        const SwappyFixedLatchObservationV1& observation) {
    if (mFatalPacingError.load(std::memory_order_acquire) ||
        !fixedLatchObservationValid(observation)) {
        return failFixedPhase(
            FixedPhaseFatalReason::ADMISSION_IDENTITY_MISMATCH);
    }
    bool exact = false;
    {
        std::lock_guard<std::mutex> lock(mFixedLatchObservationMutex);
        for (auto& record : mFixedLatchObservations) {
            if (record.state == FixedLatchRecordState::EMPTY ||
                !fixedFrameIdentityExact(
                    record.expected, observation.identity)) {
                continue;
            }
            if (record.state != FixedLatchRecordState::EXPECTED ||
                record.observationCount != 0) {
                record.state = FixedLatchRecordState::FAILED;
                break;
            }
            record.observation = observation;
            record.observationCount = 1;
            record.state = FixedLatchRecordState::OBSERVED;
            exact = true;
            break;
        }
    }
    if (!exact) {
        return failFixedPhase(
            FixedPhaseFatalReason::ADMISSION_IDENTITY_MISMATCH);
    }
    std::optional<SwappyFixedWakeNotice> joinNotice;
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        if (mFixedSubmittedRetirement.has_value() &&
            fixedFrameIdentityExact(
                mFixedSubmittedRetirement->appliedBufferRef.identity,
                observation.identity)) {
            mFixedObservedPriorLatchSnapshot = observation;
            SwappyFixedWakeNotice joined{};
            bool callbackDispatchRequired = false;
            if (publishClaimedFixedOpportunityIfJoinOpenLocked(
                    monotonicNowNanos(), &joined,
                    &callbackDispatchRequired) &&
                callbackDispatchRequired) {
                joinNotice = joined;
            }
        }
    }
    if (joinNotice.has_value()) {
        fixedPhaseOpportunityCallbacks(&*joinNotice);
    }
    return !mFatalPacingError.load(std::memory_order_acquire);
}

bool SwappyCommon::abortPreparedFixedFrameForNtk(
    std::uint64_t workGeneration) {
    if (workGeneration == 0) return false;
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        if (!mFixedPreparedFrame.has_value() ||
            mFixedPreparedFrame->workGeneration != workGeneration ||
            mFixedPreparedFrame->commitInFlight ||
            (mFixedAdmissionToken.has_value() &&
             mFixedAdmissionToken->workGeneration == workGeneration)) {
            return false;
        }
        mFixedPreparedFrame.reset();
        mFixedAvailableCandidate.reset();
        mFixedClaimedCandidate.reset();
        mFixedPublishedOpportunity.reset();
        mFixedRefreshTicket = {};
    }
    if (mChoreographerThread) {
        const FixedDemandMutationResult mutation =
            mChoreographerThread->cancelFixedFrameDemandForNtk(
                FIXED_DEMAND_OPPORTUNITY);
        if (!mutation.accepted ||
            (mutation.outstandingMask & FIXED_DEMAND_OPPORTUNITY) != 0 ||
            !fixedDemandLedgerConserved(mutation.ledgerAfter)) {
            return failFixedPhase(
                FixedPhaseFatalReason::CONSERVATION_FAILURE);
        }
    }
    return true;
}

FixedPhaseAdmissionStatus
SwappyCommon::commitPreparedFixedFrameForNtk(
    const FixedOpportunityIdentity& expected,
    FixedPhaseAdmissionToken* out,
    const SwappyFixedExternalTransportReady& transportReady) {
    const std::int64_t commonCommitEntryNanos = monotonicNowNanos();
    if (out) *out = FixedPhaseAdmissionToken{};
    const std::uint64_t workGeneration = expected.workGeneration;
    if (!out || !expected.valid() || commonCommitEntryNanos <= 0 ||
        !fixedExternalTransportReadyValid(transportReady) ||
        transportReady.workGeneration != workGeneration) {
        failFixedPhase(FixedPhaseFatalReason::ADMISSION_IDENTITY_MISMATCH);
        return FixedPhaseAdmissionStatus::FATAL;
    }
    if (mFatalPacingError.load(std::memory_order_acquire)) {
        return FixedPhaseAdmissionStatus::FATAL;
    }

    FixedPhasePlanInput input{};
    bool configValid = false;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        configValid = mFixedNonPipelineMode &&
            mFixedPhaseConfigurationValid &&
            validateFixedConfigurationLocked(mSwapDuration) &&
            !mPresentationTimeNeeded;
        input.refreshPeriodNanos = mCommonSettings.refreshPeriod.count();
        input.appVsyncOffsetNanos =
            mCommonSettings.appVsyncOffset.count();
        input.presentationDeadlineNanos =
            mCommonSettings.presentationDeadline.count();
    }
    configValid = configValid &&
        transportReady.profile.refreshPeriodNanos ==
            input.refreshPeriodNanos &&
        transportReady.profile.appVsyncOffsetNanos ==
            input.appVsyncOffsetNanos &&
        transportReady.profile.presentationDeadlineNanos ==
            input.presentationDeadlineNanos &&
        transportReady.profile.transportBoundNanos ==
            input.refreshPeriodNanos / 2;

    const auto rollbackPreToken = [this, workGeneration]() {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        if (mFixedPreparedFrame.has_value() &&
            mFixedPreparedFrame->workGeneration == workGeneration &&
            !mFixedAdmissionToken.has_value()) {
            mFixedPreparedFrame->commitInFlight = false;
            mFixedPreparedFrame->state = mFixedClaimedCandidate.has_value()
                ? FixedProducerState::GPU_READY_CLAIMED
                : FixedProducerState::GPU_READY_NO_CANDIDATE;
        }
    };

    for (;;) {
        FixedRawCandidate claimed{};
        FixedPublishedOpportunity opportunity{};
        FixedSubmittedRetirement prior{};
        SwappyFixedLatchObservationV1 priorLatchObservationAtClaim{};
        bool hasPrior = false;
        bool priorLatchObservedAtClaim = false;
        bool identityInvalid = false;
        // fixedPhaseOpportunityCallbacks() exposes JOIN_OPEN to the renderer
        // inside the tracer and persists rendererCallbackObservedNanos after
        // that tracer returns.  A retirement-thread dispatch can wake the
        // render thread in between those operations.  Snapshot the admission
        // only after the complete callback/ack handoff; this is synchronization
        // of one authority transfer, not a pacing wait or relaxed admission.
        std::unique_lock<std::mutex> handoffLock(
            mFixedOpportunityHandoffMutex);
        {
            std::lock_guard<std::mutex> lock(mWaitingMutex);
            if (!mFixedPreparedFrame.has_value() ||
                mFixedPreparedFrame->workGeneration != workGeneration ||
                mFixedPreparedFrame->reservationSequence !=
                    expected.reservationSequence ||
                mFixedPreparedFrame->gpuProofGeneration != workGeneration ||
                !mFixedPreparedFrame->gpuProofReady ||
                !fixedExternalTransportReadyExact(
                    transportReady, mFixedPreparedFrame->transportReady) ||
                workGeneration <= mLastAdmittedWorkGeneration ||
                mFixedPreparedFrame->commitInFlight ||
                mFixedAdmissionToken.has_value()) {
                identityInvalid = true;
            } else if (mFixedSubmittedRetirement.has_value() &&
                       mFixedSubmittedRetirement->state ==
                           FixedRetirementState::FATAL) {
                mFatalPacingError.store(true, std::memory_order_release);
                return FixedPhaseAdmissionStatus::FATAL;
            } else if (!mFixedClaimedCandidate.has_value() ||
                       !mFixedPublishedOpportunity.has_value() ||
                       !fixedOpportunityRendererObservedExact(
                           *mFixedPublishedOpportunity)) {
                identityInvalid = true;
            } else {
                const FixedSubmittedRetirement* priorReference =
                    mFixedSubmittedRetirement.has_value()
                        ? &*mFixedSubmittedRetirement : nullptr;
                if (priorReference != nullptr) {
                    if (priorReference->state != FixedRetirementState::RETIRED ||
                        !priorReference->terminalPublicationComplete ||
                        !fixedPriorRetirementProofValid(
                            priorReference->immutableProof) ||
                        !fixedAppliedBufferRefExact(
                            priorReference->immutableProof.predecessor,
                            priorReference->appliedBufferRef) ||
                        !fixedAppliedBufferRefExact(
                            transportReady.previousAppliedBufferRef,
                            priorReference->appliedBufferRef) ||
                        transportReady.firstStage != 0 ||
                        mFixedPublishedOpportunity->priorLatchGateRequired != 1 ||
                        mFixedPublishedOpportunity->priorLatchGateUsed != 1 ||
                        mFixedPublishedOpportunity->priorLatchWaitCount > 1 ||
                        !fixedLatchObservationValid(
                            mFixedPublishedOpportunity
                                ->priorLatchObservation) ||
                        !fixedFrameIdentityExact(
                            mFixedPublishedOpportunity
                                ->priorLatchObservation.identity,
                            priorReference->appliedBufferRef.identity)) {
                        identityInvalid = true;
                    }
                } else if (transportReady.firstStage != 1 ||
                           !fixedAppliedBufferRefEmpty(
                               transportReady.previousAppliedBufferRef) ||
                           mFixedPublishedOpportunity
                                   ->priorLatchGateRequired != 0 ||
                           mFixedPublishedOpportunity->priorLatchGateUsed != 0 ||
                           mFixedPublishedOpportunity->priorLatchWaitCount != 0 ||
                           fixedLatchObservationValid(
                               mFixedPublishedOpportunity
                                   ->priorLatchObservation)) {
                    identityInvalid = true;
                }
                if (!identityInvalid) {
                    claimed = *mFixedClaimedCandidate;
                    opportunity = *mFixedPublishedOpportunity;
                    const SwappyFixedWakeNotice& join = opportunity.wakeNotice;
                    if (claimed.workGeneration != workGeneration ||
                        claimed.reservationSequence !=
                            expected.reservationSequence ||
                        claimed.candidateSequence !=
                            expected.candidateSequence ||
                        opportunity.workGeneration != workGeneration ||
                        opportunity.reservationSequence !=
                            expected.reservationSequence ||
                        opportunity.opportunitySequence !=
                            expected.opportunitySequence ||
                        opportunity.candidateSequence !=
                            expected.candidateSequence ||
                        join.noticeSequence != expected.noticeSequence ||
                        opportunity.candidateSequence !=
                            claimed.candidateSequence ||
                        opportunity.raw.sequence != claimed.raw.sequence) {
                        identityInvalid = true;
                    } else {
                        mFixedPreparedFrame->commitInFlight = true;
                        if (priorReference != nullptr) {
                            prior = *priorReference;
                            hasPrior = true;
                            priorLatchObservationAtClaim =
                                opportunity.priorLatchObservation;
                            priorLatchObservedAtClaim =
                                fixedLatchObservationValid(
                                    priorLatchObservationAtClaim) &&
                                fixedFrameIdentityExact(
                                    priorLatchObservationAtClaim.identity,
                                    priorReference->appliedBufferRef.identity);
                            if (!priorLatchObservedAtClaim) {
                                identityInvalid = true;
                            }
                        }
                    }
                }
            }
        }
        handoffLock.unlock();
        if (identityInvalid) {
            rollbackPreToken();
            failFixedPhase(
                FixedPhaseFatalReason::ADMISSION_IDENTITY_MISMATCH);
            return FixedPhaseAdmissionStatus::FATAL;
        }
        input.acceptedFrameTimeNanos = claimed.raw.frameTimeNanos;
        input.acceptedFrameIndex = claimed.raw.frameIndex;
        const std::int64_t initialDecisionNanos = monotonicNowNanos();
        input.decisionNanos = initialDecisionNanos;
        if (priorLatchObservedAtClaim &&
            priorLatchObservationAtClaim.callbackObservedNanos >
                initialDecisionNanos) {
            rollbackPreToken();
            failFixedPhase(
                FixedPhaseFatalReason::ADMISSION_IDENTITY_MISMATCH);
            return FixedPhaseAdmissionStatus::FATAL;
        }
        const FixedExternalTransportAdmission initialAdmission =
            classifyFixedExternalTransportAdmission(
                input, transportReady.profile.transportBoundNanos);
        if (!configValid || initialDecisionNanos <= 0 ||
            !initialAdmission.valid) {
            rollbackPreToken();
            failFixedPhase(FixedPhaseFatalReason::INVALID_CONFIGURATION);
            return FixedPhaseAdmissionStatus::FATAL;
        }

        std::int64_t finalDecisionNanos = initialDecisionNanos;
        std::int64_t gateWaitTargetNanos = 0;
        std::int64_t gateWaitReturnNanos = 0;
        std::uint32_t phaseWaitCount = 0;
        if (initialAdmission.outcome ==
                FixedExternalTransportAdmissionOutcome::
                    DEFER_TO_CASE2_GATE) {
            gateWaitTargetNanos = initialAdmission.case2GateNanos;
            phaseWaitCount = 1;
            const std::int64_t waitEntryNanos = monotonicNowNanos();
            const std::int64_t waitDistanceNanos =
                gateWaitTargetNanos - waitEntryNanos;
            if (waitDistanceNanos > input.refreshPeriodNanos) {
                __android_log_print(
                    ANDROID_LOG_ERROR, LOG_TAG,
                    "diagnostic oversized fixed case2 wait work=%llu "
                    "frame=%lld receipt=%lld entry=%lld target=%lld "
                    "distance=%lld period=%lld rawSequence=%llu "
                    "physicalSequence=%llu",
                    static_cast<unsigned long long>(workGeneration),
                    static_cast<long long>(claimed.raw.frameTimeNanos),
                    static_cast<long long>(claimed.raw.callbackReceiptNanos),
                    static_cast<long long>(waitEntryNanos),
                    static_cast<long long>(gateWaitTargetNanos),
                    static_cast<long long>(waitDistanceNanos),
                    static_cast<long long>(input.refreshPeriodNanos),
                    static_cast<unsigned long long>(claimed.raw.sequence),
                    static_cast<unsigned long long>(
                        claimed.raw.physicalCallbackSequence));
            }
            const int waitResult =
                absoluteMonotonicWaitOnce(gateWaitTargetNanos);
            const std::int64_t waitReturnNanos = monotonicNowNanos();
            if (waitResult != 0) {
                __android_log_print(
                    ANDROID_LOG_ERROR, LOG_TAG,
                    "fixed case2 absolute wait failed result=%d error=%s "
                    "target=%lld entry=%lld return=%lld initial=%lld "
                    "gate=%lld cutoff=%lld latest=%lld bound=%lld",
                    waitResult, strerror(waitResult),
                    static_cast<long long>(gateWaitTargetNanos),
                    static_cast<long long>(waitEntryNanos),
                    static_cast<long long>(waitReturnNanos),
                    static_cast<long long>(initialDecisionNanos),
                    static_cast<long long>(initialAdmission.case2GateNanos),
                    static_cast<long long>(initialAdmission.case2CutoffNanos),
                    static_cast<long long>(
                        initialAdmission.case2LatestStartExclusiveNanos),
                    static_cast<long long>(
                        initialAdmission.transportBoundNanos));
                SWAPPY_LOGE(
                    "fixed case2 absolute wait failed result=%d error=%s "
                    "target=%lld entry=%lld return=%lld initial=%lld "
                    "outcome=%d gate=%lld cutoff=%lld latest=%lld bound=%lld",
                    waitResult, strerror(waitResult),
                    static_cast<long long>(gateWaitTargetNanos),
                    static_cast<long long>(waitEntryNanos),
                    static_cast<long long>(waitReturnNanos),
                    static_cast<long long>(initialDecisionNanos),
                    static_cast<int>(initialAdmission.outcome),
                    static_cast<long long>(initialAdmission.case2GateNanos),
                    static_cast<long long>(initialAdmission.case2CutoffNanos),
                    static_cast<long long>(
                        initialAdmission.case2LatestStartExclusiveNanos),
                    static_cast<long long>(
                        initialAdmission.transportBoundNanos));
                rollbackPreToken();
                failFixedPhase(
                    FixedPhaseFatalReason::ABSOLUTE_WAIT_FAILED);
                return FixedPhaseAdmissionStatus::FATAL;
            }
            finalDecisionNanos = monotonicNowNanos();
            gateWaitReturnNanos = finalDecisionNanos;
        }
        input.decisionNanos = finalDecisionNanos;
        const FixedExternalTransportAdmission finalAdmission =
            classifyFixedExternalTransportAdmissionAtDecision(
                initialAdmission, finalDecisionNanos);
        const bool closed = finalDecisionNanos <= 0 ||
            !finalAdmission.valid ||
            finalAdmission.outcome ==
                FixedExternalTransportAdmissionOutcome::
                    SLOT_CLOSED_NO_ATTEMPT;
        if (closed) {
            return finishClosedOpportunityForNtk(
                expected, finalDecisionNanos);
        }

        if (!finalAdmission.claimMayBeIssued ||
            finalAdmission.outcome ==
                FixedExternalTransportAdmissionOutcome::
                    DEFER_TO_CASE2_GATE) {
            __android_log_print(
                ANDROID_LOG_ERROR, LOG_TAG,
                "fixed case2 wait returned without claim "
                "initial=%lld final=%lld target=%lld delta=%lld outcome=%d "
                "valid=%d claim=%d gate=%lld cutoff=%lld latest=%lld "
                "bound=%lld",
                static_cast<long long>(initialDecisionNanos),
                static_cast<long long>(finalDecisionNanos),
                static_cast<long long>(gateWaitTargetNanos),
                static_cast<long long>(
                    finalDecisionNanos - gateWaitTargetNanos),
                static_cast<int>(finalAdmission.outcome),
                finalAdmission.valid ? 1 : 0,
                finalAdmission.claimMayBeIssued ? 1 : 0,
                static_cast<long long>(finalAdmission.case2GateNanos),
                static_cast<long long>(finalAdmission.case2CutoffNanos),
                static_cast<long long>(
                    finalAdmission.case2LatestStartExclusiveNanos),
                static_cast<long long>(finalAdmission.transportBoundNanos));
            SWAPPY_LOGE(
                "fixed case2 wait returned without claim admission "
                "initial=%lld final=%lld target=%lld outcome=%d valid=%d "
                "claim=%d gate=%lld cutoff=%lld latest=%lld bound=%lld",
                static_cast<long long>(initialDecisionNanos),
                static_cast<long long>(finalDecisionNanos),
                static_cast<long long>(gateWaitTargetNanos),
                static_cast<int>(finalAdmission.outcome),
                finalAdmission.valid ? 1 : 0,
                finalAdmission.claimMayBeIssued ? 1 : 0,
                static_cast<long long>(finalAdmission.case2GateNanos),
                static_cast<long long>(finalAdmission.case2CutoffNanos),
                static_cast<long long>(
                    finalAdmission.case2LatestStartExclusiveNanos),
                static_cast<long long>(finalAdmission.transportBoundNanos));
            rollbackPreToken();
            failFixedPhase(FixedPhaseFatalReason::ABSOLUTE_WAIT_FAILED);
            return FixedPhaseAdmissionStatus::FATAL;
        }

        // A deferred claim belongs to the raw opportunity classified above.
        // Replanning from the post-wait clock sample can advance P1 by one
        // refresh and silently retarget the same prepared frame.  Anchor the
        // pure planner at the frozen gate, then validate the real return time
        // against that immutable plan.
        FixedPhasePlanInput planningInput = input;
        if (initialAdmission.outcome ==
                FixedExternalTransportAdmissionOutcome::
                    DEFER_TO_CASE2_GATE) {
            planningInput.decisionNanos = gateWaitTargetNanos;
        }
        const FixedPhasePlan plan =
            planFixedNonPipelinePhase(planningInput);
        const FixedPhaseRuntimeValidation validation =
            validateFixedNonPipelinePreSwap(plan, finalDecisionNanos);
        const bool expectedCase = finalAdmission.outcome ==
                FixedExternalTransportAdmissionOutcome::
                    CASE1_TRANSPORT_PROVABLE
            ? plan.outcome == FixedPhasePlanOutcome::BEFORE_CUTOFF
            : plan.outcome == FixedPhasePlanOutcome::PROVEN_MISS;
        if (!plan.valid || plan.absoluteWaitRequired || !validation.valid ||
            !expectedCase) {
            rollbackPreToken();
            failFixedPhase(FixedPhaseFatalReason::PLAN_REJECTED);
            return FixedPhaseAdmissionStatus::FATAL;
        }

        // Publish the immutable decision/plan before validating the Android
        // FrameTimeline authority.  A rejected authority is itself terminal
        // evidence and must retain the exact plan that it failed to bind.
        {
            std::lock_guard<std::mutex> lock(mFixedPhaseTelemetryMutex);
            if (FixedGenerationTelemetryRecord* record =
                    findFixedTelemetryLocked(workGeneration)) {
                record->plan = plan;
                record->phase.schemaVersion =
                    SWAPPY_FIXED_PHASE_TELEMETRY_VERSION;
                record->phase.commonCommitEntryNanos = commonCommitEntryNanos;
                record->phase.transportProfileDigest =
                    transportReady.profile.profileDigest;
                record->phase.timingGeneration =
                    transportReady.profile.timingGeneration;
                record->phase.transportBoundNanos =
                    transportReady.profile.transportBoundNanos;
                record->phase.initialDecisionNanos = initialDecisionNanos;
                record->phase.case1CutoffNanos =
                    initialAdmission.earliestCutoffNanos;
                record->phase.case2PhaseOpenNanos =
                    initialAdmission.case2PhaseOpenNanos;
                record->phase.case2GateNanos =
                    initialAdmission.case2GateNanos;
                record->phase.case2CutoffNanos =
                    initialAdmission.case2CutoffNanos;
                record->phase.case2LatestStartExclusiveNanos =
                    initialAdmission.case2LatestStartExclusiveNanos;
                record->phase.case1LatestSafeDecisionNanos =
                    initialAdmission.case1LatestSafeDecisionNanos;
                record->phase.initialTransportAdmissionOutcome =
                    static_cast<std::int32_t>(initialAdmission.outcome);
                record->phase.phaseWaitCount = phaseWaitCount;
                record->phase.case2GateWaitTargetNanos = gateWaitTargetNanos;
                record->phase.case2GateWaitReturnNanos = gateWaitReturnNanos;
                record->phase.finalDecisionNanos = finalDecisionNanos;
                record->phase.plannerInvocationCount = 1;
                record->phase.claimIssuedCount = 0;
                record->phase.transactionPrepareBeginNanos =
                    transportReady.prepareBeginNanos;
                record->phase.transactionPrepareEndNanos =
                    transportReady.prepareEndNanos;
                copyPlanToTelemetry(input, plan, &record->phase);
            }
        }

        FixedFrameTimelineTuple selectedTimeline{};
        const bool timelineExact = selectExactFixedFrameTimeline(
            claimed.raw.frameTimelines, plan.plannedPresentationNanos,
            input.presentationDeadlineNanos, &selectedTimeline);
        if (!timelineExact) {
            const bool closedTimelineWindow =
                fixedFrameTimelineWindowClosedBeforePlan(
                    claimed.raw.frameTimelines,
                    plan.plannedPresentationNanos,
                    input.presentationDeadlineNanos);
            if (closedTimelineWindow) {
                return finishClosedOpportunityForNtk(
                    expected, finalDecisionNanos);
            }
            __android_log_print(
                ANDROID_LOG_ERROR, LOG_TAG,
                "FATAL: exact FrameTimeline unavailable work=%llu raw=%llu "
                "frame=%lld index=%lld decision=%lld planned=%lld D=%lld "
                "preferredVsync=%lld preferredExpected=%lld preferredD=%lld "
                "tuples=%zu",
                static_cast<unsigned long long>(workGeneration),
                static_cast<unsigned long long>(claimed.raw.sequence),
                static_cast<long long>(claimed.raw.frameTimeNanos),
                static_cast<long long>(claimed.raw.frameIndex),
                static_cast<long long>(finalDecisionNanos),
                static_cast<long long>(plan.plannedPresentationNanos),
                static_cast<long long>(input.presentationDeadlineNanos),
                static_cast<long long>(claimed.raw.frameTimelineVsyncId),
                static_cast<long long>(
                    claimed.raw.timelineExpectedPresentationNanos),
                static_cast<long long>(
                    claimed.raw.timelinePresentationDeadlineNanos),
                claimed.raw.frameTimelines.size());
            for (std::size_t index = 0;
                 index < claimed.raw.frameTimelines.size(); ++index) {
                const FixedFrameTimelineTuple& tuple =
                    claimed.raw.frameTimelines[index];
                __android_log_print(
                    ANDROID_LOG_ERROR, LOG_TAG,
                    "FATAL: FrameTimeline tuple[%zu] vsync=%lld "
                    "expected=%lld deadline=%lld D=%lld deltaToPlan=%lld",
                    index, static_cast<long long>(tuple.vsyncId),
                    static_cast<long long>(tuple.expectedPresentationNanos),
                    static_cast<long long>(tuple.deadlineNanos),
                    static_cast<long long>(
                        tuple.expectedPresentationNanos -
                        tuple.deadlineNanos),
                    static_cast<long long>(
                        tuple.expectedPresentationNanos -
                        plan.plannedPresentationNanos));
            }
            rollbackPreToken();
            failFixedPhase(
                FixedPhaseFatalReason::INVALID_FRAME_TIMELINE_AUTHORITY);
            return FixedPhaseAdmissionStatus::FATAL;
        }
        claimed.raw.frameTimelineVsyncId = selectedTimeline.vsyncId;
        claimed.raw.timelineExpectedPresentationNanos =
            selectedTimeline.expectedPresentationNanos;
        claimed.raw.timelinePresentationDeadlineNanos =
            selectedTimeline.expectedPresentationNanos -
                selectedTimeline.deadlineNanos;
        claimed.raw.hasTimeline = true;

        bool configurationStillExact = false;
        {
            std::lock_guard<std::mutex> lock(mMutex);
            configurationStillExact = mFixedNonPipelineMode &&
                mFixedPhaseConfigurationValid &&
                mCommonSettings.refreshPeriod.count() ==
                    transportReady.profile.refreshPeriodNanos &&
                mCommonSettings.appVsyncOffset.count() ==
                    transportReady.profile.appVsyncOffsetNanos &&
                mCommonSettings.presentationDeadline.count() ==
                    transportReady.profile.presentationDeadlineNanos;
        }
        if (!configurationStillExact) {
            rollbackPreToken();
            failFixedPhase(FixedPhaseFatalReason::INVALID_CONFIGURATION);
            return FixedPhaseAdmissionStatus::FATAL;
        }

        bool telemetryRecordPresent = false;
        {
            std::lock_guard<std::mutex> lock(mFixedPhaseTelemetryMutex);
            FixedGenerationTelemetryRecord* record =
                findFixedTelemetryLocked(workGeneration);
            telemetryRecordPresent = record != nullptr;
            if (record != nullptr) {
                // The immutable decision/plan was published before timeline
                // authority validation so fatal and successful paths expose
                // the same evidence.
            }
        }
        if (!telemetryRecordPresent) {
            rollbackPreToken();
            failFixedPhase(FixedPhaseFatalReason::CONSERVATION_FAILURE);
            return FixedPhaseAdmissionStatus::FATAL;
        }

        FixedPhaseAdmissionToken token{};
        token.priorRetirementProof = emptyFixedPriorRetirementProof();
        if (hasPrior) {
            if (!priorLatchObservedAtClaim) {
                rollbackPreToken();
                failFixedPhase(
                    FixedPhaseFatalReason::ADMISSION_IDENTITY_MISMATCH);
                return FixedPhaseAdmissionStatus::FATAL;
            }
            token.priorLatchObservation =
                priorLatchObservationAtClaim;
            token.priorLatchObservedAtClaim = true;
            token.priorCommitProofPendingAtClaim = false;
            token.priorLatchWaitCount = opportunity.priorLatchWaitCount;
        }
        bool finalIdentityExact = false;
        {
            std::lock_guard<std::mutex> lock(mWaitingMutex);
            const bool priorExact = hasPrior
                ? (mFixedSubmittedRetirement.has_value() &&
                   mFixedSubmittedRetirement->workGeneration ==
                       prior.workGeneration &&
                   mFixedSubmittedRetirement->admissionSequence ==
                       prior.admissionSequence &&
                   mFixedSubmittedRetirement->retirementSequence ==
                       prior.retirementSequence &&
                   mFixedSubmittedRetirement->state ==
                       FixedRetirementState::RETIRED &&
                   mFixedSubmittedRetirement->terminalPublicationComplete)
                : !mFixedSubmittedRetirement.has_value();
            finalIdentityExact = mFixedPreparedFrame.has_value() &&
                mFixedPreparedFrame->workGeneration == workGeneration &&
                mFixedPreparedFrame->commitInFlight &&
                !mFixedAdmissionToken.has_value() &&
                fixedExternalTransportReadyExact(
                    transportReady, mFixedPreparedFrame->transportReady) &&
                mFixedClaimedCandidate.has_value() &&
                mFixedClaimedCandidate->candidateSequence ==
                    claimed.candidateSequence &&
                mFixedClaimedCandidate->raw.sequence == claimed.raw.sequence &&
                mFixedPublishedOpportunity.has_value() &&
                mFixedPublishedOpportunity->opportunitySequence ==
                    opportunity.opportunitySequence &&
                mFixedPublishedOpportunity->priorLatchGateRequired ==
                    opportunity.priorLatchGateRequired &&
                mFixedPublishedOpportunity->priorLatchGateUsed ==
                    opportunity.priorLatchGateUsed &&
                mFixedPublishedOpportunity->priorLatchWaitCount ==
                    opportunity.priorLatchWaitCount &&
                (hasPrior
                    ? fixedLatchObservationExact(
                        mFixedPublishedOpportunity->priorLatchObservation,
                        opportunity.priorLatchObservation)
                    : fixedLatchObservationEmpty(
                          mFixedPublishedOpportunity
                              ->priorLatchObservation) &&
                      fixedLatchObservationEmpty(
                          opportunity.priorLatchObservation)) &&
                opportunity.opportunitySequence ==
                    expected.opportunitySequence &&
                opportunity.candidateSequence == expected.candidateSequence &&
                opportunity.wakeNotice.noticeSequence ==
                    expected.noticeSequence && priorExact;
            if (finalIdentityExact && hasPrior) {
                // JOIN_OPEN freezes the exact proof; successful admission is
                // its sole consumer.  A second successor cannot reuse the
                // same compositor callback as commit authority.
                finalIdentityExact =
                    consumeFixedLatchObservationForSuccessor(
                        prior.appliedBufferRef.identity,
                        opportunity.priorLatchObservation);
            }
            if (finalIdentityExact) {
                token.sequence = ++mFixedAdmissionSequence;
                token.workGeneration = workGeneration;
                token.reservationSequence = claimed.reservationSequence;
                token.candidateSequence = claimed.candidateSequence;
                token.opportunitySequence = opportunity.opportunitySequence;
                token.opportunityKind = expected.opportunitySequence == 1
                    ? FixedOpportunityKind::FIRST
                    : FixedOpportunityKind::NEXT;
                token.candidateCaptureNanos = claimed.capturedNanos;
                token.candidateClaimNanos = claimed.claimedNanos;
                token.shadowRawSequence = mFixedAvailableCandidate.has_value()
                    ? mFixedAvailableCandidate->raw.sequence : 0;
                token.shadowPromotionCount = mFixedShadowPromotionCount;
                token.refresh = mFixedRefreshTicket;
                token.joinNotice = opportunity.wakeNotice;
                if (hasPrior) {
                    token.priorRetirementWorkGeneration = prior.workGeneration;
                    token.priorRetirementAdmissionSequence =
                        prior.admissionSequence;
                    token.priorRetirementSequence = prior.retirementSequence;
                    token.priorRetirementProof = prior.immutableProof;
                }
                token.raw = claimed.raw;
                token.input = input;
                token.plan = plan;
                token.transportReady = transportReady;
                token.transportAdmission = finalAdmission;
                token.commonCommitEntryNanos = commonCommitEntryNanos;
                token.initialDecisionNanos = initialDecisionNanos;
                token.case2GateWaitTargetNanos = gateWaitTargetNanos;
                token.case2GateWaitReturnNanos = gateWaitReturnNanos;
                token.phaseWaitCount = phaseWaitCount;
                token.consumed = true;
                token.gpuProofReady = true;
                token.gpuProofGeneration = workGeneration;
                mFixedPreparedFrame->commitInFlight = true;
                mFixedPreparedFrame->state = FixedProducerState::TOKEN_ISSUED;
                mFixedAdmissionToken = token;
                mFixedAdmissionPreSwapCommitted = true;
            }
        }
        if (!finalIdentityExact) {
            __android_log_print(
                ANDROID_LOG_ERROR, LOG_TAG,
                "qualification timing final-identity-failed work=%llu "
                "expectedReservation=%llu expectedOpportunity=%llu "
                "expectedCandidate=%llu expectedNotice=%llu hasPrior=%d",
                static_cast<unsigned long long>(workGeneration),
                static_cast<unsigned long long>(
                    expected.reservationSequence),
                static_cast<unsigned long long>(
                    expected.opportunitySequence),
                static_cast<unsigned long long>(
                    expected.candidateSequence),
                static_cast<unsigned long long>(expected.noticeSequence),
                hasPrior ? 1 : 0);
            rollbackPreToken();
            failFixedPhase(
                FixedPhaseFatalReason::ADMISSION_IDENTITY_MISMATCH);
            return FixedPhaseAdmissionStatus::FATAL;
        }

        SwappyFixedFrameIdentityV1 ownIdentity{};
        ownIdentity.structSize = sizeof(ownIdentity);
        ownIdentity.version = SWAPPY_FIXED_FRAME_IDENTITY_V1_VERSION;
        ownIdentity.engineGeneration = transportReady.engineGeneration;
        ownIdentity.surfaceEpoch = transportReady.surfaceEpoch;
        ownIdentity.authorityGeneration =
            transportReady.authorityGeneration;
        ownIdentity.authority = transportReady.authority;
        ownIdentity.workGeneration = token.workGeneration;
        ownIdentity.ntkFrameId = transportReady.ntkFrameId;
        ownIdentity.frameSequence = transportReady.frameSequence;
        ownIdentity.admissionSequence = token.sequence;
        ownIdentity.capsuleSequence = transportReady.capsuleSequence;
        ownIdentity.backendSurfaceSerial =
            transportReady.backendSurfaceSerial;
        ownIdentity.transactionSerial = transportReady.transactionSerial;
        ownIdentity.bufferSlot = transportReady.bufferSlot;
        ownIdentity.bufferGeneration = transportReady.bufferGeneration;
        ownIdentity.frameTimelineVsyncId = token.raw.frameTimelineVsyncId;
        if (!registerFixedLatchExpectation(ownIdentity)) {
            failFixedPhase(FixedPhaseFatalReason::CONSERVATION_FAILURE);
            return FixedPhaseAdmissionStatus::FATAL;
        }

        {
            std::lock_guard<std::mutex> lock(mFixedPhaseTelemetryMutex);
            if (FixedGenerationTelemetryRecord* record =
                    findFixedTelemetryLocked(workGeneration)) {
                record->phase.claimIssuedCount = 1;
            }
        }

        if (workGeneration >= 45) {
            __android_log_print(
                ANDROID_LOG_INFO, LOG_TAG,
                "qualification timing claim work=%llu admission=%llu "
                "rawReceipt=%lld decision=%lld planned=%lld claimAt=%lld "
                "phaseWait=%u",
                static_cast<unsigned long long>(workGeneration),
                static_cast<unsigned long long>(token.sequence),
                static_cast<long long>(claimed.raw.callbackReceiptNanos),
                static_cast<long long>(finalDecisionNanos),
                static_cast<long long>(plan.plannedPresentationNanos),
                static_cast<long long>(monotonicNowNanos()),
                phaseWaitCount);
        }
        *out = token;
        return FixedPhaseAdmissionStatus::ADMITTED;
    }
}

void SwappyCommon::markFixedPhaseSubmissionFailureForNtk() {
    failFixedPhase(FixedPhaseFatalReason::SUBMISSION_FAILED);
}

bool SwappyCommon::commitFixedPreSwapTimestamp() {
    bool admissionConsumed = false;
    bool preSwapAlreadyCommitted = false;
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        admissionConsumed = mFixedAdmissionToken.has_value() &&
            mFixedAdmissionToken->consumed;
        preSwapAlreadyCommitted = admissionConsumed &&
            mFixedAdmissionPreSwapCommitted;
    }
    if (!admissionConsumed) {
        return failFixedPhase(
            FixedPhaseFatalReason::ADMISSION_TOKEN_MISSING);
    }
    // The prepared phase-commit boundary already performed the sole runtime
    // validation using the exact decision/preSwap sample.  Re-entering either
    // the clock or validator here would recreate a post-token failure gap.
    if (preSwapAlreadyCommitted) return true;

    const std::int64_t preSwapNanos = monotonicNowNanos();
    FixedPhaseRuntimeValidation validation;
    bool phaseMissProven = false;
    {
        std::lock_guard<std::mutex> lock(mFixedPhaseTelemetryMutex);
        FixedGenerationTelemetryRecord* record = activeFixedTelemetryLocked();
        if (!record) {
            mFatalPacingError.store(true, std::memory_order_release);
            return false;
        }
        validation = validateFixedNonPipelinePreSwap(
            record->plan, preSwapNanos);
        phaseMissProven = record->plan.phaseMissProven;
        if (validation.valid) {
            record->phase.preSubmitNanos = preSwapNanos;
        } else {
            record->phase.fatalReason = static_cast<std::int32_t>(
                phaseMissProven ? FixedPhaseFatalReason::LATE_WAKE
                                : FixedPhaseFatalReason::
                                      CUTOFF_PASSED_BEFORE_PRESWAP);
        }
    }
    if (!validation.valid) {
        // Never retarget Case 1 to Case 2 after this frame's plan is published.
        mFatalPacingError.store(true, std::memory_order_release);
    }
    return validation.valid;
}

FixedPostSwapStamp SwappyCommon::beginFixedPostSwapForNtk(
    const FixedPhaseAdmissionToken& token,
    std::int64_t finalCorridorBeginNanos,
    std::int64_t queueMarkNanos,
    std::int64_t eglSwapEnterNanos) {
    FixedPostSwapStamp stamp{};
    if (token.sequence == 0 || token.workGeneration == 0 ||
        finalCorridorBeginNanos <= 0 || queueMarkNanos <= 0 ||
        eglSwapEnterNanos < queueMarkNanos ||
        token.input.decisionNanos < finalCorridorBeginNanos) {
        return stamp;
    }
    stamp.finalCorridorBeginNanos = finalCorridorBeginNanos;
    stamp.decisionNanos = token.input.decisionNanos;
    stamp.queueMarkNanos = queueMarkNanos;
    stamp.eglSwapEnterNanos = eglSwapEnterNanos;
    stamp.postSwapNanos = monotonicNowNanos();
    if (stamp.postSwapNanos <= 0) return stamp;
    const FixedPhaseRuntimeValidation validation =
        validateFixedNonPipelinePostSwap(
            token.plan, token.input.decisionNanos,
            stamp.postSwapNanos);
    stamp.phaseValid = validation.valid;
    stamp.outcome = static_cast<std::int32_t>(validation.outcome);
    if (!validation.valid) {
        stamp.fatalReason = static_cast<std::int32_t>(
            validation.outcome ==
                    FixedPhaseRuntimeOutcome::FATAL_SWAP_MISSED_CUTOFF
                ? FixedPhaseFatalReason::SWAP_MISSED_CUTOFF
                : FixedPhaseFatalReason::SWAP_DURATION_INVALID);
    }
    return stamp;
}

bool SwappyCommon::finishFixedPostSwapForNtk(
    const FixedPhaseAdmissionToken& token,
    const FixedPostSwapStamp& stamp,
    SwappyFixedPhaseTelemetry* outPhase,
    const SwappyFixedExternalClaim& externalClaim,
    const SwappyFixedExternalSubmission& externalSubmission,
    std::uint64_t* outRetirementSequence) {
    if (outPhase) *outPhase = {};
    if (outRetirementSequence) *outRetirementSequence = 0;
    const std::uint64_t admissionSequence = token.sequence;
    const std::uint64_t workGeneration = token.workGeneration;
    const bool externalClaimExact = externalClaim.claimToken == token.sequence &&
        externalClaim.workGeneration == token.workGeneration &&
        externalClaim.admissionSequence == token.sequence;
    const bool transportBoundExceeded = externalClaim.transportBoundNanos <= 0 ||
        externalSubmission.transactionApplyEndNanos <
            externalClaim.decisionNanos ||
        externalSubmission.transactionApplyEndNanos -
                externalClaim.decisionNanos >
            externalClaim.transportBoundNanos;
    const bool externalProofExact = externalClaimExact &&
        fixedExternalSubmissionExact(externalClaim, externalSubmission);
    const std::int32_t exactPostSubmitFatalReason = stamp.fatalReason != 0
        ? stamp.fatalReason
        : (transportBoundExceeded
            ? static_cast<std::int32_t>(
                  FixedPhaseFatalReason::TRANSPORT_BOUND_EXCEEDED)
            : (!externalProofExact || stamp.postSwapNanos <= 0
                ? static_cast<std::int32_t>(
                      FixedPhaseFatalReason::ADMISSION_IDENTITY_MISMATCH)
                : 0));

    const FixedDemandLedgerSnapshot initialDemandLedger = mChoreographerThread
        ? mChoreographerThread->getFixedDemandLedgerForNtk()
        : FixedDemandLedgerSnapshot{};
    SwappyFixedPhaseTelemetry phase{};
    {
        std::lock_guard<std::mutex> lock(mFixedPhaseTelemetryMutex);
        if (FixedGenerationTelemetryRecord* record =
                findFixedTelemetryLocked(workGeneration)) {
            phase = record->phase;
        }
    }
    phase.schemaVersion = SWAPPY_FIXED_PHASE_TELEMETRY_VERSION;
    phase.admissionSequence = token.sequence;
    phase.workGeneration = token.workGeneration;
    phase.rawAuthoritySequence = token.raw.sequence;
    phase.reservationSequence = token.reservationSequence;
    phase.opportunitySequence = token.opportunitySequence;
    phase.opportunityKind = static_cast<std::int32_t>(token.opportunityKind);
    phase.priorRetirementWorkGeneration =
        token.priorRetirementWorkGeneration;
    phase.priorRetirementAdmissionSequence =
        token.priorRetirementAdmissionSequence;
    phase.priorRetirementSequence = token.priorRetirementSequence;
    phase.priorRetirementProof = token.priorRetirementProof;
    phase.priorRetirementProofPresent =
        token.priorRetirementProof.hasPrior == 1 ? 1U : 0U;
    phase.priorLatchGateRequired =
        token.priorRetirementProof.hasPrior == 1 ? 1U : 0U;
    phase.priorLatchGateUsed =
        token.priorRetirementProof.hasPrior == 1 ? 1U : 0U;
    phase.priorLatchWaitCount = token.priorLatchWaitCount;
    phase.priorLatchObservationState =
        token.priorRetirementProof.hasPrior == 0
            ? SWAPPY_FIXED_PRIOR_LATCH_OBSERVATION_NONE
            : SWAPPY_FIXED_PRIOR_LATCH_OBSERVED_AT_CLAIM;
    phase.priorCommitProofPendingAtClaim =
        token.priorCommitProofPendingAtClaim ? 1U : 0U;
    if (token.priorLatchObservedAtClaim) {
        phase.priorLatchObservation = token.priorLatchObservation;
    }
    phase.physicalCallbackSequence = token.raw.physicalCallbackSequence;
    phase.plannerInvocationCount = 1;
    phase.closedNoAttemptCount = 0;
    phase.admissionStatus = static_cast<std::int32_t>(
        FixedPhaseAdmissionStatus::ADMITTED);
    phase.admissionConsumed = 1;
    phase.candidateSequence = token.candidateSequence;
    phase.candidateRawSequence = token.raw.sequence;
    phase.candidateCaptureNanos = token.candidateCaptureNanos;
    phase.candidateClaimNanos = token.candidateClaimNanos;
    phase.candidateClaimReason = 1;
    phase.shadowRawSequence = token.shadowRawSequence;
    phase.shadowPromotionCount = token.shadowPromotionCount;
    phase.refreshIssued = token.refresh.issued ? 1U : 0U;
    phase.refreshDelivered = token.refresh.delivered ? 1U : 0U;
    phase.refreshPhysicalCallbackSequence =
        token.refresh.physicalCallbackSequence;
    phase.refreshCapturedRawSequence = token.refresh.capturedRawSequence;
    phase.joinNoticeSequence = token.joinNotice.noticeSequence;
    phase.joinOpenNanos = token.joinNotice.opportunityPublishNanos;
    phase.joinPriorRetirementSequence =
        token.joinNotice.priorRetirementSequence;
    if (token.priorLatchObservedAtClaim) {
        phase.latchEventWorkGeneration =
            token.priorLatchObservation.identity.workGeneration;
        phase.latchEventNtkFrameId =
            token.priorLatchObservation.identity.ntkFrameId;
        phase.latchEventSurfaceEpoch =
            token.priorLatchObservation.identity.surfaceEpoch;
        phase.latchEventAdmissionSequence =
            token.priorLatchObservation.identity.admissionSequence;
        phase.latchEventSequence =
            token.priorLatchObservation.latchEventSequence;
        phase.latchEventTransactionSerial =
            token.priorLatchObservation.identity.transactionSerial;
        phase.latchEventCompositorNanos =
            token.priorLatchObservation.compositorLatchNanos;
        phase.latchEventSource = token.priorLatchObservation.source;
    }
    phase.finalCorridorBeginNanos = stamp.finalCorridorBeginNanos;
    phase.transactionApplyBeginNanos =
        externalSubmission.transactionApplyBeginNanos;
    phase.transactionApplyEndNanos =
        externalSubmission.transactionApplyEndNanos;
    phase.decisionToApplyBeginNanos =
        externalSubmission.transactionApplyBeginNanos -
            token.input.decisionNanos;
    phase.commonCommitEntryNanos = token.commonCommitEntryNanos;
    phase.opportunityClaimNanos = token.candidateClaimNanos;
    phase.opportunityReceiptNanos = token.raw.callbackReceiptNanos;
    phase.opportunityPublishNanos =
        token.joinNotice.opportunityPublishNanos;
    phase.wakeNoticeSequence = token.joinNotice.noticeSequence;
    phase.retirementStageNanos = token.joinNotice.retirementStageNanos;
    phase.demandMutationCompleteNanos =
        token.joinNotice.demandMutationCompleteNanos;
    phase.terminalVisibleNanos = token.joinNotice.terminalVisibleNanos;
    phase.wakeDispatchNanos = token.joinNotice.wakeDispatchNanos;
    phase.rendererCallbackObservedNanos =
        token.joinNotice.rendererCallbackObservedNanos;
    phase.rendererWakeObservedNanos =
        token.joinNotice.rendererCallbackObservedNanos;
    phase.preSubmitNanos = token.input.decisionNanos;
    phase.postSubmitNanos = stamp.postSwapNanos;
    phase.submitDurationNanos =
        stamp.postSwapNanos - token.input.decisionNanos;
    phase.outcome = stamp.outcome;
    phase.fatalReason = exactPostSubmitFatalReason;
    phase.phaseFatalReason = exactPostSubmitFatalReason;
    phase.gpuFenceWaitCount = 0;
    phase.targetRebaseCount = 0;
    phase.externalBackendSurfaceSerial =
        externalSubmission.backendSurfaceSerial;
    phase.externalTransactionSerial =
        externalSubmission.transactionSerial;
    phase.externalWorkGeneration = externalSubmission.workGeneration;
    phase.externalNtkFrameId = externalSubmission.ntkFrameId;
    phase.gpuRenderBeginNanos = externalSubmission.gpuRenderBeginNanos;
    phase.gpuRenderEndNanos = externalSubmission.gpuRenderEndNanos;
    phase.gpuFenceIssuedNanos = externalSubmission.gpuFenceIssuedNanos;
    phase.gpuFenceWaitReturnNanos =
        externalSubmission.gpuFenceWaitReturnNanos;
    phase.acquireFenceSerial = externalSubmission.acquireFenceSerial;
    phase.acquireFenceDupCount = externalSubmission.acquireFenceDupCount;
    phase.frameworkTransferCount =
        externalSubmission.frameworkTransferCount;
    phase.rendererGpuClientWaitCount =
        externalSubmission.rendererGpuClientWaitCount;
    phase.setBufferPending = 0;
    phase.setBufferCount = externalSubmission.setBufferCount;
    phase.transactionApplyCount = externalSubmission.transactionApplyCount;
    phase.setFrameTimelineCount =
        externalSubmission.setFrameTimelineCount;
    phase.applyDisposition = externalSubmission.applyDisposition;
    phase.transactionPrepareBeginNanos =
        externalSubmission.transactionPrepareBeginNanos;
    phase.transactionPrepareEndNanos =
        externalSubmission.transactionPrepareEndNanos;
    phase.decisionToClaimReturnNanos =
        externalClaim.claimReturnNanos -
            token.input.decisionNanos;
    phase.applyCallDurationNanos =
        externalSubmission.transactionApplyEndNanos -
            externalSubmission.transactionApplyBeginNanos;
    phase.decisionToApplyEndNanos =
        externalSubmission.transactionApplyEndNanos -
            token.input.decisionNanos;
    phase.transportBoundSlackNanos =
        token.transportReady.profile.transportBoundNanos -
            phase.decisionToApplyEndNanos;
    phase.cutoffSlackNanos = token.plan.plannedCutoffNanos -
        externalSubmission.transactionApplyEndNanos;
    phase.retirementDemandIssued = initialDemandLedger.retirementIssued;
    phase.retirementDemandSatisfied = initialDemandLedger.retirementSatisfied;
    phase.retirementDemandCancelled = initialDemandLedger.retirementCancelled;
    phase.opportunityDemandIssued = initialDemandLedger.opportunityIssued;
    phase.opportunityDemandSatisfied = initialDemandLedger.opportunitySatisfied;
    phase.opportunityDemandCancelled = initialDemandLedger.opportunityCancelled;
    phase.supersededBeforeClaimCount = mFixedSupersededBeforeClaimCount;
    phase.closedOpportunityCount = mFixedClosedOpportunityCount;
    copyPlanToTelemetry(token.input, token.plan, &phase);

    {
        std::lock_guard<std::mutex> lock(mFixedPhaseTelemetryMutex);
        phase.sequence = ++mFixedPhaseSequence;
        FixedGenerationTelemetryRecord* record =
            findFixedTelemetryLocked(workGeneration);
        if (record != nullptr) {
            record->plan = token.plan;
            record->phase = phase;
        }
    }
    FixedSubmittedRetirement submitted{};
    SwappyFixedLatchObservationV1 alreadyObservedLatch{};
    const FixedLatchLookupResult ownLatchState =
        snapshotFixedLatchObservation(
            externalSubmission.appliedBufferRef.identity,
            &alreadyObservedLatch);
    bool identityInvalid =
        ownLatchState == FixedLatchLookupResult::MISSING ||
        ownLatchState == FixedLatchLookupResult::FAILED;
    bool requestRetirement = false;
    bool terminalCandidate = false;
    bool terminalRetired = false;
    bool retirementCountExact = false;
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        const bool predecessorExpected =
            token.priorRetirementWorkGeneration != 0;
        const bool predecessorExact =
            (predecessorExpected
                ? (mFixedSubmittedRetirement.has_value() &&
                   mFixedSubmittedRetirement->state ==
                       FixedRetirementState::RETIRED &&
                   mFixedSubmittedRetirement->terminalPublicationComplete &&
                   mFixedSubmittedRetirement->workGeneration ==
                       token.priorRetirementWorkGeneration &&
                   mFixedSubmittedRetirement->admissionSequence ==
                       token.priorRetirementAdmissionSequence &&
                   mFixedSubmittedRetirement->retirementSequence ==
                       token.priorRetirementSequence)
                : !mFixedSubmittedRetirement.has_value());
        if (identityInvalid || !token.consumed || !token.gpuProofReady ||
            token.gpuProofGeneration != workGeneration ||
            token.plan.plannedTargetFrame <= 0 ||
            !mFixedPreparedFrame.has_value() ||
            mFixedPreparedFrame->workGeneration != workGeneration ||
            mFixedPreparedFrame->state != FixedProducerState::TOKEN_ISSUED ||
            !mFixedPreparedFrame->commitInFlight) {
            identityInvalid = true;
        } else {
            submitted.retirementSequence = ++mFixedRetirementSequence;
            submitted.admissionSequence = admissionSequence;
            submitted.workGeneration = workGeneration;
            submitted.frameId = token.transportReady.ntkFrameId;
            submitted.engineGeneration =
                token.transportReady.engineGeneration;
            submitted.surfaceEpoch = token.transportReady.surfaceEpoch;
            submitted.authorityGeneration =
                token.transportReady.authorityGeneration;
            submitted.authority = token.transportReady.authority;
            submitted.frameSequence = token.transportReady.frameSequence;
            submitted.capsuleSequence = token.transportReady.capsuleSequence;
            submitted.backendSurfaceSerial =
                token.transportReady.backendSurfaceSerial;
            submitted.transactionSerial =
                token.transportReady.transactionSerial;
            submitted.bufferSlot = token.transportReady.bufferSlot;
            submitted.bufferGeneration =
                token.transportReady.bufferGeneration;
            submitted.appliedBufferRef =
                externalSubmission.appliedBufferRef;
            submitted.frameTimelineVsyncId = token.raw.frameTimelineVsyncId;
            submitted.rawAuthoritySequence =
                token.raw.sequence;
            submitted.plannedTargetFrame =
                token.plan.plannedTargetFrame;
            submitted.originalTargetFrame =
                token.plan.plannedTargetFrame;
            submitted.postSwapNanos = stamp.postSwapNanos;
            submitted.waitBeginNanos = monotonicNowNanos();
            submitted.externalProofPublished = externalProofExact;
            submitted.targetWaitCount = 1;
            submitted.targetRebaseCount = 0;
            const bool queueBoundaryRetired = qualifiesFixedTargetAuthority(
                submitted, mRawFixedFrameAuthority);
            const bool postSwapProofFatal = !predecessorExact ||
                !externalProofExact || !stamp.phaseValid ||
                exactPostSubmitFatalReason != 0;
            if (postSwapProofFatal || queueBoundaryRetired) {
                terminalCandidate = true;
                terminalRetired = !postSwapProofFatal;
                submitted.state = FixedRetirementState::PUBLISHING;
                submitted.terminalPublicationComplete = false;
            }
            if (queueBoundaryRetired) {
                submitted.targetAuthorityRawSequence =
                    mRawFixedFrameAuthority.sequence;
                submitted.targetPhysicalCallbackSequence =
                    mRawFixedFrameAuthority.physicalCallbackSequence;
                submitted.targetFrameTimeNanos =
                    mRawFixedFrameAuthority.frameTimeNanos;
                submitted.targetFrameIndex =
                    mRawFixedFrameAuthority.frameIndex;
                submitted.targetAuthorityNanos =
                    mRawFixedFrameAuthority.callbackReceiptNanos;
                submitted.targetReachedNanos =
                    mRawFixedFrameAuthority.callbackReceiptNanos;
                submitted.retirementPublishNanos = monotonicNowNanos();
                submitted.retirementCompleteNanos =
                    submitted.retirementPublishNanos;
                submitted.retirementStageNanos =
                    submitted.retirementPublishNanos;
            } else if (!postSwapProofFatal) {
                submitted.state = FixedRetirementState::WAIT_ARMED;
                requestRetirement = true;
            } else {
                submitted.fatalReason = exactPostSubmitFatalReason != 0
                    ? exactPostSubmitFatalReason
                    : static_cast<std::int32_t>(
                          FixedPhaseFatalReason::
                              ADMISSION_IDENTITY_MISMATCH);
            }
            mFixedObservedPriorLatchSnapshot.reset();
            mFixedSubmittedRetirement = submitted;
            if (ownLatchState == FixedLatchLookupResult::OBSERVED) {
                mFixedObservedPriorLatchSnapshot = alreadyObservedLatch;
            }
            ++mFixedSubmittedCount;
            retirementCountExact =
                mFixedTargetRetiredCount + 1 <= mFixedSubmittedCount &&
                mFixedSubmittedCount - (mFixedTargetRetiredCount + 1) <= 1;
            mFixedPreparedFrame->state = FixedProducerState::SUBMITTED;
            mLastAdmittedWorkGeneration = workGeneration;
            mFixedLastDisposedRawSequence = std::max(
                mFixedLastDisposedRawSequence, token.raw.sequence);
            mFixedPreparedFrame.reset();
            mFixedAvailableCandidate.reset();
            mFixedClaimedCandidate.reset();
            mFixedPublishedOpportunity.reset();
            mFixedRefreshTicket = {};
            mFixedAdmissionToken.reset();
            mFixedAdmissionOriginalTargetFrame = 0;
            mFixedAdmissionPreSwapCommitted = false;
        }
    }
    if (identityInvalid || submitted.waitBeginNanos <= 0) {
        return failFixedPhase(
            FixedPhaseFatalReason::ADMISSION_IDENTITY_MISMATCH);
    }
    if (outRetirementSequence) {
        *outRetirementSequence = submitted.retirementSequence;
    }
    FixedDemandMutationResult demandMutation{};
    if (requestRetirement) {
        if (mUsingExternalChoreographer || !mChoreographerThread) {
            terminalCandidate = true;
            terminalRetired = false;
            submitted.state = FixedRetirementState::PUBLISHING;
            submitted.fatalReason = static_cast<std::int32_t>(
                FixedPhaseFatalReason::NO_RAW_FRAME_AUTHORITY);
            requestRetirement = false;
            std::lock_guard<std::mutex> lock(mWaitingMutex);
            if (mFixedSubmittedRetirement.has_value() &&
                mFixedSubmittedRetirement->workGeneration == workGeneration &&
                mFixedSubmittedRetirement->state ==
                    FixedRetirementState::WAIT_ARMED) {
                *mFixedSubmittedRetirement = submitted;
            }
        } else {
            demandMutation =
                mChoreographerThread->requestFixedFrameCallbackForNtk(
                    FIXED_DEMAND_RETIREMENT);
            if (!demandMutation.accepted) {
                terminalCandidate = true;
                terminalRetired = false;
                submitted.state = FixedRetirementState::PUBLISHING;
                submitted.fatalReason = static_cast<std::int32_t>(
                    FixedPhaseFatalReason::SUBMISSION_FAILED);
                requestRetirement = false;
                std::lock_guard<std::mutex> lock(mWaitingMutex);
                if (mFixedSubmittedRetirement.has_value() &&
                    mFixedSubmittedRetirement->workGeneration ==
                        workGeneration &&
                    mFixedSubmittedRetirement->state ==
                        FixedRetirementState::WAIT_ARMED) {
                    *mFixedSubmittedRetirement = submitted;
                }
            }
        }
    } else if (mChoreographerThread) {
        demandMutation = mChoreographerThread->requestFixedFrameCallbackForNtk(
            FIXED_DEMAND_NONE);
    } else {
        demandMutation.accepted = true;
    }

    if (requestRetirement) {
        const FixedDemandLedgerSnapshot& ledger = demandMutation.ledgerAfter;
        {
            std::lock_guard<std::mutex> lock(mWaitingMutex);
            if (mFixedSubmittedRetirement.has_value() &&
                mFixedSubmittedRetirement->workGeneration == workGeneration &&
                mFixedSubmittedRetirement->state ==
                    FixedRetirementState::WAIT_ARMED) {
                mFixedSubmittedRetirement->retirementDemandIssued =
                    ledger.retirementIssued;
                mFixedSubmittedRetirement->retirementDemandSatisfied =
                    ledger.retirementSatisfied;
                mFixedSubmittedRetirement->retirementDemandCancelled =
                    ledger.retirementCancelled;
                submitted = *mFixedSubmittedRetirement;
            }
        }
        {
            std::lock_guard<std::mutex> lock(mFixedPhaseTelemetryMutex);
            FixedGenerationTelemetryRecord* record =
                findFixedTelemetryLocked(workGeneration);
            if (record) {
                record->phase.retirementDemandIssued =
                    ledger.retirementIssued;
                record->phase.retirementDemandSatisfied =
                    ledger.retirementSatisfied;
                record->phase.retirementDemandCancelled =
                    ledger.retirementCancelled;
                record->phase.opportunityDemandIssued =
                    ledger.opportunityIssued;
                record->phase.opportunityDemandSatisfied =
                    ledger.opportunitySatisfied;
                record->phase.opportunityDemandCancelled =
                    ledger.opportunityCancelled;
            }
        }
    }

    if (terminalCandidate) {
        const FixedDemandLedgerSnapshot& ledger = demandMutation.ledgerAfter;
        submitted.retirementDemandIssued = ledger.retirementIssued;
        submitted.retirementDemandSatisfied = ledger.retirementSatisfied;
        submitted.retirementDemandCancelled = ledger.retirementCancelled;
        submitted.demandMutationCompleteNanos =
            demandMutation.mutationCompleteNanos > 0
                ? demandMutation.mutationCompleteNanos
                : monotonicNowNanos();
        submitted.terminalVisibleNanos = monotonicNowNanos();
        submitted.rendererWakePublishNanos = submitted.terminalVisibleNanos;
        const bool demandExact =
            submitted.retirementDemandIssued ==
                submitted.retirementDemandSatisfied +
                    submitted.retirementDemandCancelled;
        bool terminalExact = terminalRetired && demandMutation.accepted &&
            retirementCountExact && demandExact &&
            submitted.targetAuthorityNanos >= submitted.postSwapNanos &&
            submitted.targetReachedNanos == submitted.targetAuthorityNanos &&
            submitted.retirementPublishNanos >=
                submitted.targetAuthorityNanos &&
            submitted.retirementCompleteNanos ==
                submitted.retirementPublishNanos &&
            submitted.retirementStageNanos ==
                submitted.retirementPublishNanos &&
            submitted.demandMutationCompleteNanos >=
                submitted.retirementStageNanos &&
            submitted.terminalVisibleNanos >=
                submitted.demandMutationCompleteNanos &&
            submitted.rendererWakePublishNanos >=
                submitted.terminalVisibleNanos;
        submitted.state = terminalExact
            ? FixedRetirementState::RETIRED : FixedRetirementState::FATAL;
        if (terminalExact &&
            !sealFixedPriorRetirementProof(&submitted)) {
            terminalExact = false;
            submitted.state = FixedRetirementState::FATAL;
        }
        SwappyFixedLatchObservationV1 observedPhysical{};
        const FixedLatchLookupResult physicalState =
            snapshotFixedLatchObservation(
                submitted.appliedBufferRef.identity, &observedPhysical);
        if (terminalExact &&
            physicalState != FixedLatchLookupResult::PENDING &&
            physicalState != FixedLatchLookupResult::OBSERVED) {
            terminalExact = false;
            submitted.state = FixedRetirementState::FATAL;
        }
        submitted.terminalPublicationComplete = true;
        if (!terminalExact && submitted.fatalReason == 0) {
            submitted.fatalReason = static_cast<std::int32_t>(
                FixedPhaseFatalReason::SUBMISSION_FAILED);
        }
        {
            std::lock_guard<std::mutex> lock(mFixedPhaseTelemetryMutex);
            FixedGenerationTelemetryRecord* record =
                findFixedTelemetryLocked(workGeneration);
            if (record) {
                record->phase.retirementDemandIssued = ledger.retirementIssued;
                record->phase.retirementDemandSatisfied =
                    ledger.retirementSatisfied;
                record->phase.retirementDemandCancelled =
                    ledger.retirementCancelled;
                record->phase.opportunityDemandIssued = ledger.opportunityIssued;
                record->phase.opportunityDemandSatisfied =
                    ledger.opportunitySatisfied;
                record->phase.opportunityDemandCancelled =
                    ledger.opportunityCancelled;
            }
        }
        bool terminalRepublishRequired = false;
        {
            std::lock_guard<std::mutex> lock(mWaitingMutex);
            const bool exactPublishing = mFixedSubmittedRetirement.has_value() &&
                mFixedSubmittedRetirement->workGeneration == workGeneration &&
                mFixedSubmittedRetirement->retirementSequence ==
                    submitted.retirementSequence &&
                mFixedSubmittedRetirement->state ==
                    FixedRetirementState::PUBLISHING;
            if (exactPublishing) {
                *mFixedSubmittedRetirement = submitted;
                if (submitted.state == FixedRetirementState::RETIRED) {
                    ++mFixedTargetRetiredCount;
                    if (physicalState ==
                        FixedLatchLookupResult::OBSERVED) {
                        mFixedObservedPriorLatchSnapshot = observedPhysical;
                    }
                } else {
                    mFatalPacingError.store(true, std::memory_order_release);
                }
            } else {
                submitted.state = FixedRetirementState::FATAL;
                if (submitted.fatalReason == 0) {
                    submitted.fatalReason = static_cast<std::int32_t>(
                        FixedPhaseFatalReason::SUBMISSION_FAILED);
                }
                submitted.terminalPublicationComplete = true;
                if (mFixedSubmittedRetirement.has_value() &&
                    mFixedSubmittedRetirement->workGeneration ==
                        workGeneration) {
                    *mFixedSubmittedRetirement = submitted;
                }
                terminalRepublishRequired = true;
                mFatalPacingError.store(true, std::memory_order_release);
            }
            mWaitingCondition.notify_all();
        }
        (void)terminalRepublishRequired;
        finishFixedFrameStatistics(submitted);
    }
    if (!stamp.phaseValid || !externalProofExact ||
        submitted.state == FixedRetirementState::FATAL) {
        mFatalPacingError.store(true, std::memory_order_release);
    }
    {
        std::lock_guard<std::mutex> lock(mFixedPhaseTelemetryMutex);
        if (FixedGenerationTelemetryRecord* record =
                findFixedTelemetryLocked(workGeneration)) {
            record->phase.phaseFatalReason = exactPostSubmitFatalReason;
            record->phase.retirementFatalReason = submitted.fatalReason;
            record->phase.retirementCallbackPublishCount =
                submitted.retirementCallbackPublishCount;
        }
    }
    if (outPhase) {
        std::lock_guard<std::mutex> lock(mFixedPhaseTelemetryMutex);
        if (FixedGenerationTelemetryRecord* record =
                findFixedTelemetryLocked(workGeneration)) {
            *outPhase = record->phase;
        }
    }
    return stamp.phaseValid && externalProofExact &&
        submitted.state != FixedRetirementState::FATAL;
}

bool SwappyCommon::abortClaimedExternalFixedFrameForNtk(
        std::uint64_t workGeneration) {
    if (workGeneration == 0) return false;
    SwappyFixedFrameIdentityV1 expectation{};
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        if (!mFixedPreparedFrame.has_value() ||
            mFixedPreparedFrame->workGeneration != workGeneration ||
            mFixedPreparedFrame->state != FixedProducerState::TOKEN_ISSUED ||
            !mFixedPreparedFrame->commitInFlight ||
            !mFixedAdmissionToken.has_value() ||
            mFixedAdmissionToken->workGeneration != workGeneration) {
            return false;
        }
        const FixedPhaseAdmissionToken& token = *mFixedAdmissionToken;
        expectation.structSize = sizeof(expectation);
        expectation.version = SWAPPY_FIXED_FRAME_IDENTITY_V1_VERSION;
        expectation.engineGeneration =
            token.transportReady.engineGeneration;
        expectation.surfaceEpoch = token.transportReady.surfaceEpoch;
        expectation.authorityGeneration =
            token.transportReady.authorityGeneration;
        expectation.authority = token.transportReady.authority;
        expectation.workGeneration = token.workGeneration;
        expectation.ntkFrameId = token.transportReady.ntkFrameId;
        expectation.frameSequence = token.transportReady.frameSequence;
        expectation.admissionSequence = token.sequence;
        expectation.capsuleSequence = token.transportReady.capsuleSequence;
        expectation.backendSurfaceSerial =
            token.transportReady.backendSurfaceSerial;
        expectation.transactionSerial =
            token.transportReady.transactionSerial;
        expectation.bufferSlot = token.transportReady.bufferSlot;
        expectation.bufferGeneration = token.transportReady.bufferGeneration;
        expectation.frameTimelineVsyncId = token.raw.frameTimelineVsyncId;
        mFixedPreparedFrame.reset();
        mFixedAvailableCandidate.reset();
        mFixedClaimedCandidate.reset();
        mFixedPublishedOpportunity.reset();
        mFixedRefreshTicket = {};
        mFixedAdmissionToken.reset();
        mFixedAdmissionOriginalTargetFrame = 0;
        mFixedAdmissionPreSwapCommitted = false;
    }
    return discardFixedLatchExpectation(expectation);
}

bool SwappyCommon::waitForNextFrame(const SwapHandlers& h) {
    int lateFrames = 0;
    bool presentationTimeIsNeeded;

    // We do not want to hold the mutex while waiting, so make a local copy of
    // the flags.
    mMutex.lock();
    bool localAutoSwapIntervalEnabled = mAutoSwapIntervalEnabled;
    bool localFramePacingEnabled = mFramePacingEnabled;
    bool localFixedNonPipelineMode = mFixedNonPipelineMode;
    PipelineMode localPipelineMode = mPipelineMode;
    int localAutoSwapInterval = mAutoSwapInterval;

    // We do the blocking wait when pacing or request by the app when not
    // pacing.
    // Fixed non-pipeline pacing always owns both waits: Choreographer selects the target display
    // period, then the caller thread continuously waits for the exact EGL fence. The latter must
    // never be rounded up through waitOneFrame(), which can add an entire refresh period.
    bool localBlockingWaitEnabled = localFixedNonPipelineMode || mBlockingWaitEnabled ||
        (mFramePacingEnabled && localPipelineMode == PipelineMode::On);
    mMutex.unlock();

    const nanoseconds cpuTime =
        (mStartFrameTime.time_since_epoch().count() == 0)
            ? 0ns
            : std::chrono::steady_clock::now() - mStartFrameTime;
    mCPUTracer.endTrace();

    preWaitCallbacks();

    const auto legacyFrameLate = [this] {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        return mCurrentFrame > mTargetFrame;
    };

    // Fixed production never enters this stock serial wait. Its EGL_TRUE path
    // uses begin/finishFixedPostSwapForNtk and callback-bound retirement.
    // Reaching this branch proves an obsolete caller survived.
    if (localFixedNonPipelineMode) {
        return failFixedPhase(FixedPhaseFatalReason::SUBMISSION_FAILED);
    }

    // if we are running slower than the threshold (if auto swap interval is
    // enabled) there is no point to sleep,
    // just let the app run as fast as it can
    if (mCommonSettings.refreshPeriod * mAutoSwapInterval <=
            mAutoSwapIntervalThreshold.load() ||
        !localAutoSwapIntervalEnabled) {
        if (localFramePacingEnabled) waitUntilTargetFrame();

        if (localBlockingWaitEnabled) {
            // wait for the previous frame to be rendered
            while (!h.lastFrameIsComplete()) {
                lateFrames++;
                waitOneFrame();
            }
        }

        mPresentationTime += lateFrames * mCommonSettings.refreshPeriod;
        presentationTimeIsNeeded = localFramePacingEnabled;
    } else {
        presentationTimeIsNeeded = false;
    }

    // If last frame is not finished, return -1 for GPU time.
    const nanoseconds gpuTime =
        (h.lastFrameIsComplete()) ? h.getPrevFrameGpuTime() : -1ns;

    // Keep track of durations only if frame pacing is enabled.
    if (localFramePacingEnabled)
        addFrameDuration({cpuTime, gpuTime, legacyFrameLate()});

    postWaitCallbacks(cpuTime, gpuTime);

    return presentationTimeIsNeeded;
}

void SwappyCommon::updateDisplayTimings() {
    // grab a pointer to the latest supported refresh rates
    if (mDisplayManager) {
        mSupportedRefreshPeriods =
            mDisplayManager->getSupportedRefreshPeriods();
    }

    std::lock_guard<std::mutex> lock(mMutex);
    SWAPPY_LOGW_ONCE_IF(!mWindow,
                        "ANativeWindow not configured, frame rate will not be "
                        "reported to Android platform");

    if (mFramePacingToggleRequested) {
        // In case frame pacing is toggled, set the update here.
        mFramePacingEnabled = !mFramePacingEnabled;
        mFramePacingToggleRequested = false;
    }
    if (mFixedNonPipelineMode) {
        if (!mFramePacingEnabled || !mBlockingWaitEnabled) {
            mFatalPacingError.store(true, std::memory_order_release);
            return;
        }
        mFramePacingResetRequested = false;
        mTimingSettingsNeedUpdate = false;
        mWindowChanged = false;
        if (mNextTimingSettings.refreshPeriod > 0ns) {
            mCommonSettings.refreshPeriod = mNextTimingSettings.refreshPeriod;
        }
        mAutoSwapInterval = 1;
        mPipelineMode = PipelineMode::Off;
        mFrameDurations.clear();
        mFixedPhaseConfigurationValid =
            validateFixedConfigurationLocked(mSwapDuration);
        if (!mFixedPhaseConfigurationValid) {
            mFatalPacingError.store(true, std::memory_order_release);
            return;
        }
        setPreferredRefreshPeriod(mSwapDuration);
        return;
    }
    if (mFramePacingResetRequested) {
        // In case of reset, just issue the update for setting refresh period.
        setPreferredRefreshPeriod(mInitialRefreshPeriod);
        mFramePacingResetRequested = false;
        return;
    }

    if (!mFramePacingEnabled) {
        return;
    }

    if (!mTimingSettingsNeedUpdate && !mWindowChanged) {
        return;
    }

    mTimingSettingsNeedUpdate = false;

    if (!mWindowChanged &&
        mCommonSettings.refreshPeriod == mNextTimingSettings.refreshPeriod &&
        mSwapDuration == mNextTimingSettings.swapDuration) {
        return;
    }

    mWindowChanged = false;
    mCommonSettings.refreshPeriod = mNextTimingSettings.refreshPeriod;

    const auto pipelineFrameTime =
        mFrameDurations.getAverageFrameTime().getTime(PipelineMode::On);
    const auto swapDuration =
        pipelineFrameTime != 0ns ? pipelineFrameTime : mSwapDuration;
    mAutoSwapInterval =
        calculateSwapInterval(swapDuration, mCommonSettings.refreshPeriod);
    mPipelineMode = PipelineMode::Off;

    const bool swapIntervalValid =
        mNextTimingSettings.refreshPeriod * mAutoSwapInterval >=
        mNextTimingSettings.swapDuration;
    const bool swapIntervalChangedBySettings =
        mSwapDuration != mNextTimingSettings.swapDuration;

    mSwapDuration = mNextTimingSettings.swapDuration;
    if (!mAutoSwapIntervalEnabled || swapIntervalChangedBySettings ||
        !swapIntervalValid) {
        mAutoSwapInterval =
            calculateSwapInterval(mSwapDuration, mCommonSettings.refreshPeriod);
    mPipelineMode = PipelineMode::Off;
        setPreferredRefreshPeriod(mSwapDuration);
    }

    if (mNextModeId == -1 && mLatestFrameRateVote == 0) {
        setPreferredRefreshPeriod(mSwapDuration);
    }

    // MangaViewer qualification is a fixed 90 Hz, non-pipelined contract. The stock adaptive
    // controller may double mAutoSwapInterval after transient decoder/upload pressure even when
    // the public maximum-auto interval is one display period. Keep the local fork at exactly one
    // physical refresh; the non-pipeline wait still runs after every swap.
    mAutoSwapInterval = 1;
    mPipelineMode = PipelineMode::Off;
    mFrameDurations.clear();

    TRACE_INT("mSwapDuration", int(mSwapDuration.count()));
    TRACE_INT("mAutoSwapInterval", mAutoSwapInterval);
    TRACE_INT("mCommonSettings.refreshPeriod",
              mCommonSettings.refreshPeriod.count());
    TRACE_INT("mPipelineMode", static_cast<int>(mPipelineMode));
}

bool SwappyCommon::onPreSwap(const SwapHandlers& h) {
    bool fixedNonPipelineMode = false;
    PipelineMode pipelineMode = PipelineMode::Off;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        fixedNonPipelineMode = mFixedNonPipelineMode;
        pipelineMode = mPipelineMode;
        if (fixedNonPipelineMode && mPresentationTimeNeeded) {
            return failFixedPhase(
                FixedPhaseFatalReason::PRESENTATION_TIME_FORBIDDEN);
        }
    }

    // Prepared fixed commit has already selected its one natural raw
    // opportunity, performed any phase wait, sampled the final clock, and
    // consumed its token.  Do not enqueue any additional Choreographer work
    // in this post-token interval.
    if (!fixedNonPipelineMode && !mUsingExternalChoreographer) {
        mChoreographerThread->postFrameCallbacks();
    }

    // Fixed mode never plans here.  The renderer has already frozen and drawn
    // one immutable work generation, while the prepared phase-commit boundary
    // issued and consumed its exact token using the stored preSwap sample.
    if (fixedNonPipelineMode) {
        // Fixed admission owns the exact target and explicitly forbids per-frame PTS. Preserve
        // the false value published above; the generic threshold branch must never overwrite it.
    } else if (pipelineMode == PipelineMode::On) {
        mPresentationTimeNeeded = waitForNextFrame(h);
    } else {
        mPresentationTimeNeeded =
            (mCommonSettings.refreshPeriod * mAutoSwapInterval <=
             mAutoSwapIntervalThreshold.load());
    }

    if (fixedNonPipelineMode && !commitFixedPreSwapTimestamp()) return false;
    mSwapTime = std::chrono::steady_clock::now();
    preSwapBuffersCallbacks();
    return !mFatalPacingError.load(std::memory_order_acquire);
}

bool SwappyCommon::onPostSwap(const SwapHandlers& h) {
    const std::int64_t postSwapNanos = monotonicNowNanos();
    const auto swapEnd = std::chrono::steady_clock::now();
    // Preserve the tracer contract: this callback remains the first operation
    // after the actual eglSwapBuffers return.
    postSwapBuffersCallbacks();
    updateMeasuredSwapDuration(swapEnd - mSwapTime);

    bool fixedNonPipelineMode = false;
    PipelineMode pipelineMode = PipelineMode::Off;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        fixedNonPipelineMode = mFixedNonPipelineMode;
        pipelineMode = mPipelineMode;
    }
    const bool phaseValid = !fixedNonPipelineMode;
    if (fixedNonPipelineMode) {
        failFixedPhase(FixedPhaseFatalReason::SUBMISSION_FAILED);
    }

    bool pacingWaitValid = true;
    if (pipelineMode == PipelineMode::Off) {
        pacingWaitValid = waitForNextFrame(h);
    }

    if (pacingWaitValid && updateSwapInterval()) {
        swapIntervalChangedCallbacks();
        TRACE_INT("mPipelineMode", static_cast<int>(mPipelineMode));
        TRACE_INT("mAutoSwapInterval", mAutoSwapInterval);
    }

    if (pacingWaitValid) updateDisplayTimings();

    if (pacingWaitValid) startFrame();
    if (fixedNonPipelineMode) {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        mFixedAdmissionToken.reset();
        mFixedAdmissionOriginalTargetFrame = 0;
        mFixedAdmissionPreSwapCommitted = false;
    }
    return phaseValid && pacingWaitValid &&
        !mFatalPacingError.load(std::memory_order_acquire);
}

void SwappyCommon::updateMeasuredSwapDuration(nanoseconds duration) {
    nanoseconds refreshPeriod = 0ns;
    bool fixedNonPipelineMode = false;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        refreshPeriod = mCommonSettings.refreshPeriod;
        fixedNonPipelineMode = mFixedNonPipelineMode;
    }
    if (fixedNonPipelineMode) {
        // Fixed admission needs the exact feasibility lead on every raw opportunity. A fast
        // warm swap must not let adaptive EWMA shrink that lead and recreate late Case 1 entry.
        mMeasuredSwapDuration.store(refreshPeriod / 2, std::memory_order_release);
        return;
    }
    // TODO: The exponential smoothing factor here is arbitrary
    mMeasuredSwapDuration =
        (mMeasuredSwapDuration.load() * 4 / 5) + duration / 5;

    // Clamp the swap duration to half the refresh period
    //
    // We do this since the swap duration can be a bit noisy during periods such
    // as app startup, which can cause some stuttering as the smoothing catches
    // up with the actual duration. By clamping, we reduce the maximum error
    // which reduces the calibration time.
    if (mMeasuredSwapDuration.load() > (refreshPeriod / 2)) {
        mMeasuredSwapDuration.store(refreshPeriod / 2);
    }
}

nanoseconds SwappyCommon::getSwapDuration() {
    std::lock_guard<std::mutex> lock(mMutex);
    return mAutoSwapInterval * mCommonSettings.refreshPeriod;
};

void SwappyCommon::FrameDurations::add(FrameDuration frameDuration) {
    const auto now = std::chrono::steady_clock::now();
    mFrames.push_back({now, frameDuration});
    mFrameDurationsSum += frameDuration;
    if (frameDuration.frameMiss()) {
        mMissedFrameCount++;
    }

    while (mFrames.size() >= 2 &&
           now - (mFrames.begin() + 1)->first > FRAME_DURATION_SAMPLE_SECONDS) {
        mFrameDurationsSum -= mFrames.front().second;
        if (mFrames.front().second.frameMiss()) {
            mMissedFrameCount--;
        }
        mFrames.pop_front();
    }
}

bool SwappyCommon::FrameDurations::hasEnoughSamples() const {
    return (!mFrames.empty()) && (mFrames.back().first - mFrames.front().first >
                                  FRAME_DURATION_SAMPLE_SECONDS);
}

SwappyCommon::FrameDuration SwappyCommon::FrameDurations::getAverageFrameTime()
    const {
    if (hasEnoughSamples()) {
        return mFrameDurationsSum / mFrames.size();
    }

    return {};
}

int SwappyCommon::FrameDurations::getMissedFramePercent() const {
    return round(mMissedFrameCount * 100.0f / mFrames.size());
}

void SwappyCommon::FrameDurations::clear() {
    mFrames.clear();
    mFrameDurationsSum = {};
    mMissedFrameCount = 0;
}

void SwappyCommon::addFrameDuration(FrameDuration duration) {
    SWAPPY_LOGV("cpuTime = %.2f", duration.getCpuTime().count() / 1e6f);
    SWAPPY_LOGV("gpuTime = %.2f", duration.getGpuTime().count() / 1e6f);
    SWAPPY_LOGV("frame %s", duration.frameMiss() ? "MISS" : "on time");

    std::lock_guard<std::mutex> lock(mMutex);
    mFrameDurations.add(duration);
}

bool SwappyCommon::swapSlower(const FrameDuration& averageFrameTime,
                              const nanoseconds& upperBound,
                              int newSwapInterval) {
    bool swappedSlower = false;
    SWAPPY_LOGV("Rendering takes too much time for the given config");

    const auto frameFitsUpperBound =
        averageFrameTime.getTime(PipelineMode::On) <= upperBound;
    const auto swapDurationWithinThreshold =
        mCommonSettings.refreshPeriod * mAutoSwapInterval <=
        mAutoSwapIntervalThreshold.load() + FRAME_MARGIN;

    // Check if turning on pipeline is not enough
    if ((mPipelineMode == PipelineMode::On || !frameFitsUpperBound) &&
        swapDurationWithinThreshold) {
        int originalAutoSwapInterval = mAutoSwapInterval;
        if (newSwapInterval > mAutoSwapInterval) {
            mAutoSwapInterval = newSwapInterval;
        } else {
            mAutoSwapInterval++;
        }
        if (mAutoSwapInterval != originalAutoSwapInterval) {
            SWAPPY_LOGV("Changing Swap interval to %d from %d",
                        mAutoSwapInterval, originalAutoSwapInterval);
            swappedSlower = true;
        }
    }

    if (mPipelineMode == PipelineMode::Off) {
        SWAPPY_LOGV("turning on pipelining");
    mPipelineMode = PipelineMode::Off;
    }

    return swappedSlower;
}

bool SwappyCommon::swapFaster(int newSwapInterval) {
    bool swappedFaster = false;
    int originalAutoSwapInterval = mAutoSwapInterval;
    while (newSwapInterval < mAutoSwapInterval && swapFasterCondition()) {
        mAutoSwapInterval--;
    }

    if (mAutoSwapInterval != originalAutoSwapInterval) {
        SWAPPY_LOGV("Rendering is much shorter for the given config");
        SWAPPY_LOGV("Changing Swap interval to %d from %d", mAutoSwapInterval,
                    originalAutoSwapInterval);
        // since we changed the swap interval, we may need to turn on pipeline
        // mode
        SWAPPY_LOGV("Turning on pipelining");
    mPipelineMode = PipelineMode::Off;
        swappedFaster = true;
    }

    return swappedFaster;
}

bool SwappyCommon::updateSwapInterval() {
    std::lock_guard<std::mutex> lock(mMutex);

    if (mFixedNonPipelineMode) {
        mAutoSwapInterval = 1;
        mPipelineMode = PipelineMode::Off;
        return false;
    }

    // A request to reset the frame-pacing is made, so reset the internal swap
    // state to the initial state and clear the frame durations collected.
    if (mFramePacingResetRequested) {
        mAutoSwapInterval = 1;
        mMeasuredSwapDuration = 0ns;
        mSwapDuration = 0ns;
        mCommonSettings.refreshPeriod = mInitialRefreshPeriod;
        mFrameDurations.clear();
        return true;
    }
    // Local fixed-90 policy: never let load heuristics trade a missed deadline for a two-period
    // cadence. This is the small Swappy fork required by the renderer's qualification contract.
    if (mAutoSwapInterval != 1) {
        mAutoSwapInterval = 1;
        mPipelineMode = PipelineMode::Off;
        mFrameDurations.clear();
        return true;
    }
    mPipelineMode = PipelineMode::Off;
    return false;

#if 0  // Upstream adaptive interval controller retained below for provenance.
    if (!mAutoSwapIntervalEnabled) return false;

    if (!mFrameDurations.hasEnoughSamples()) return false;

    const auto averageFrameTime = mFrameDurations.getAverageFrameTime();
    const auto pipelineFrameTime = averageFrameTime.getTime(PipelineMode::On);
    const auto nonPipelineFrameTime =
        averageFrameTime.getTime(PipelineMode::Off);

    // calculate the new swap interval based on average frame time assume we are
    // in pipeline mode (prefer higher swap interval rather than turning off
    // pipeline mode)
    const int newSwapInterval =
        calculateSwapInterval(pipelineFrameTime, mCommonSettings.refreshPeriod);

    // Define upper and lower bounds based on the swap duration
    const nanoseconds upperBoundForThisRefresh =
        mCommonSettings.refreshPeriod * mAutoSwapInterval;
    const nanoseconds lowerBoundForThisRefresh =
        mCommonSettings.refreshPeriod * (mAutoSwapInterval - 1) - FRAME_MARGIN;

    const int missedFramesPercent = mFrameDurations.getMissedFramePercent();

    SWAPPY_LOGV("mPipelineMode = %d", static_cast<int>(mPipelineMode));
    SWAPPY_LOGV("Average cpu frame time = %.2f",
                (averageFrameTime.getCpuTime().count()) / 1e6f);
    SWAPPY_LOGV("Average gpu frame time = %.2f",
                (averageFrameTime.getGpuTime().count()) / 1e6f);
    SWAPPY_LOGV("upperBound = %.2f", upperBoundForThisRefresh.count() / 1e6f);
    SWAPPY_LOGV("lowerBound = %.2f", lowerBoundForThisRefresh.count() / 1e6f);
    SWAPPY_LOGV("frame missed = %d%%", missedFramesPercent);

    bool configChanged = false;
    SWAPPY_LOGV("pipelineFrameTime = %.2f", pipelineFrameTime.count() / 1e6f);
    const auto nonPipelinePercent = (100.f + NON_PIPELINE_PERCENT) / 100.f;

    // Make sure the frame time fits in the current config to avoid missing
    // frames
    if (missedFramesPercent > FRAME_DROP_THRESHOLD) {
        if (swapSlower(averageFrameTime, upperBoundForThisRefresh,
                       newSwapInterval))
            configChanged = true;
    }

    // So we shouldn't miss any frames with this config but maybe we can go
    // faster ? we check the pipeline frame time here as we prefer lower swap
    // interval than no pipelining
    else if (missedFramesPercent == 0 && swapFasterCondition() &&
             pipelineFrameTime < lowerBoundForThisRefresh) {
        if (swapFaster(newSwapInterval)) configChanged = true;
    }

    // If we reached to this condition it means that we fit into the boundaries.
    // However we might be in pipeline mode and we could turn it off if we still
    // fit. To be very conservative, switch to non-pipeline if frame time * 50%
    // fits
    else if (mPipelineModeAutoMode && mPipelineMode == PipelineMode::On &&
             nonPipelineFrameTime * nonPipelinePercent <
                 upperBoundForThisRefresh) {
        SWAPPY_LOGV(
            "Rendering time fits the current swap interval without pipelining");
        mPipelineMode = PipelineMode::Off;
        configChanged = true;
    }

    if (configChanged) {
        mFrameDurations.clear();
    }

    setPreferredRefreshPeriod(pipelineFrameTime);

    return configChanged;
#endif
}

template <typename Tracers, typename Func>
void addToTracers(Tracers& tracers, Func func, void* userData) {
    if (func != nullptr) {
        tracers.push_back({func, userData});
    }
}

template <typename Tracers, typename Func>
void removeFromTracers(Tracers& tracers, Func func, void* userData) {
    if (func != nullptr) {
        for (auto it = tracers.begin(); it != tracers.end();) {
            auto jt = it;
            it++;
            if (jt->function == func && jt->userData == userData) {
                tracers.erase(jt);
            }
        }
    }
}

void SwappyCommon::addTracerCallbacks(const SwappyTracer& tracer) {
    // The explicit general -> fixed order is part of the callback lifetime protocol.
    std::unique_lock<std::mutex> general_lock(mInjectedTracersMutex);
    std::unique_lock<std::mutex> fixed_opportunity_lock(
        mFixedPhaseOpportunityTracersMutex);
    addToTracers(mInjectedTracers.preWait, tracer.preWait, tracer.userData);
    addToTracers(mInjectedTracers.postWait, tracer.postWait, tracer.userData);
    addToTracers(mInjectedTracers.preSwapBuffers, tracer.preSwapBuffers,
                 tracer.userData);
    addToTracers(mInjectedTracers.postSwapBuffers, tracer.postSwapBuffers,
                 tracer.userData);
    addToTracers(mInjectedTracers.startFrame, tracer.startFrame,
                 tracer.userData);
    addToTracers(mInjectedTracers.swapIntervalChanged,
                 tracer.swapIntervalChanged, tracer.userData);
    addToTracers(mInjectedTracers.fixedRetirementCompleted,
                 tracer.fixedRetirementCompleted, tracer.userData);
    addToTracers(mFixedPhaseOpportunityTracers,
                 tracer.fixedPhaseOpportunity, tracer.userData);
}

void SwappyCommon::removeTracerCallbacks(const SwappyTracer& tracer) {
    // Use the same explicit general -> fixed order as registration. While waiting for a general
    // callback, this owns no fixed lock, so existing raw-authority observers remain live. Once
    // both locks are held, all in-flight callbacks have returned; exact removal makes this
    // function's return the no-later-callback boundary for this (function,userData).
    std::unique_lock<std::mutex> general_lock(mInjectedTracersMutex);
    std::unique_lock<std::mutex> fixed_opportunity_lock(
        mFixedPhaseOpportunityTracersMutex);
    removeFromTracers(mInjectedTracers.preWait, tracer.preWait, tracer.userData);
    removeFromTracers(mInjectedTracers.postWait, tracer.postWait, tracer.userData);
    removeFromTracers(
        mInjectedTracers.preSwapBuffers, tracer.preSwapBuffers, tracer.userData);
    removeFromTracers(
        mInjectedTracers.postSwapBuffers, tracer.postSwapBuffers, tracer.userData);
    removeFromTracers(mInjectedTracers.startFrame, tracer.startFrame, tracer.userData);
    removeFromTracers(mInjectedTracers.swapIntervalChanged,
                      tracer.swapIntervalChanged, tracer.userData);
    removeFromTracers(mInjectedTracers.fixedRetirementCompleted,
                      tracer.fixedRetirementCompleted, tracer.userData);
    removeFromTracers(mFixedPhaseOpportunityTracers,
                      tracer.fixedPhaseOpportunity, tracer.userData);
}

template <typename T, typename... Args>
void executeTracers(T& tracers, Args... args) {
    for (const auto& tracer : tracers) {
        tracer.function(tracer.userData, std::forward<Args>(args)...);
    }
}

void SwappyCommon::preSwapBuffersCallbacks() {
    std::lock_guard<std::mutex> lock(mInjectedTracersMutex);
    executeTracers(mInjectedTracers.preSwapBuffers);
}

void SwappyCommon::postSwapBuffersCallbacks() {
    std::lock_guard<std::mutex> lock(mInjectedTracersMutex);
    executeTracers(mInjectedTracers.postSwapBuffers,
                   (int64_t)mPresentationTime.time_since_epoch().count());
}

void SwappyCommon::preWaitCallbacks() {
    std::lock_guard<std::mutex> lock(mInjectedTracersMutex);
    executeTracers(mInjectedTracers.preWait);
}

void SwappyCommon::postWaitCallbacks(nanoseconds cpuTime, nanoseconds gpuTime) {
    std::lock_guard<std::mutex> lock(mInjectedTracersMutex);
    executeTracers(mInjectedTracers.postWait, cpuTime.count(), gpuTime.count());
}

void SwappyCommon::startFrameCallbacks() {
    std::lock_guard<std::mutex> lock(mInjectedTracersMutex);
    executeTracers(mInjectedTracers.startFrame,
                   static_cast<std::int32_t>(mCurrentFrame),
                   (int64_t)mPresentationTime.time_since_epoch().count());
}

void SwappyCommon::swapIntervalChangedCallbacks() {
    std::lock_guard<std::mutex> lock(mInjectedTracersMutex);
    executeTracers(mInjectedTracers.swapIntervalChanged);
}

void SwappyCommon::setAutoSwapInterval(bool enabled) {
    std::lock_guard<std::mutex> lock(mMutex);
    mAutoSwapIntervalEnabled = enabled;

    // non pipeline mode is not supported when auto mode is disabled
    if (!enabled) {
    mPipelineMode = PipelineMode::Off;
        TRACE_INT("mPipelineMode", static_cast<int>(mPipelineMode));
    }
}

void SwappyCommon::setAutoPipelineMode(bool enabled) {
    std::lock_guard<std::mutex> lock(mMutex);
    mPipelineModeAutoMode = enabled;
    TRACE_INT("mPipelineModeAutoMode", mPipelineModeAutoMode);
    if (!enabled) {
    mPipelineMode = PipelineMode::Off;
        TRACE_INT("mPipelineMode", static_cast<int>(mPipelineMode));
    }
}

void SwappyCommon::fixedPhaseOpportunityCallbacks(
    const SwappyFixedWakeNotice* exactNotice) {
    if (!exactNotice || exactNotice->structSize !=
            sizeof(SwappyFixedWakeNotice) ||
        exactNotice->version != SWAPPY_FIXED_WAKE_NOTICE_VERSION ||
        exactNotice->wakeReason != SWAPPY_FIXED_WAKE_JOIN_OPEN ||
        exactNotice->workGeneration == 0 ||
        exactNotice->reservationSequence == 0 ||
        exactNotice->opportunitySequence == 0 ||
        exactNotice->candidateSequence == 0 ||
        exactNotice->noticeSequence == 0) {
        failFixedPhase(FixedPhaseFatalReason::ADMISSION_IDENTITY_MISMATCH);
        return;
    }
    // Keep renderer publication and Common's copy-back acknowledgment atomic
    // with respect to commitPreparedFixedFrameForNtk().  The tracer itself does
    // not call back into Common; it only records the observation in this
    // payload, publishes the renderer-local gate, and wakes the render owner.
    std::unique_lock<std::mutex> handoffLock(
        mFixedOpportunityHandoffMutex);
    SwappyFixedWakeNotice notice = *exactNotice;
    std::shared_ptr<SwappyFixedWakeNotice> sharedNotice;
    std::uint64_t retirementWorkGeneration = 0;
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        const auto candidateMatches = [&notice](
                const FixedRawCandidate& candidate) {
            return candidate.workGeneration == notice.workGeneration &&
                candidate.reservationSequence == notice.reservationSequence &&
                candidate.candidateSequence == notice.candidateSequence;
        };
        if (mFixedAvailableCandidate.has_value() &&
            candidateMatches(*mFixedAvailableCandidate)) {
            sharedNotice = mFixedAvailableCandidate->wakeNotice;
        } else if (mFixedClaimedCandidate.has_value() &&
                   candidateMatches(*mFixedClaimedCandidate)) {
            sharedNotice = mFixedClaimedCandidate->wakeNotice;
        }
        if (sharedNotice) *sharedNotice = notice;
        if (mFixedPublishedOpportunity.has_value() &&
            mFixedPublishedOpportunity->workGeneration ==
                notice.workGeneration &&
            mFixedPublishedOpportunity->candidateSequence ==
                notice.candidateSequence &&
            mFixedPublishedOpportunity->opportunitySequence ==
                notice.opportunitySequence) {
            mFixedPublishedOpportunity->wakeNotice = notice;
        }
        if (mFixedSubmittedRetirement.has_value() &&
            mFixedSubmittedRetirement->retirementSequence ==
                notice.priorRetirementSequence) {
            retirementWorkGeneration =
                mFixedSubmittedRetirement->workGeneration;
            mFixedSubmittedRetirement->wakeDispatchNanos =
                notice.wakeDispatchNanos;
            mFixedSubmittedRetirement->rendererWakePublishNanos =
                notice.wakeDispatchNanos;
        }
    }
    bool hadTracer = false;
    {
        std::lock_guard<std::mutex> lock(
            mFixedPhaseOpportunityTracersMutex);
        hadTracer = !mFixedPhaseOpportunityTracers.empty();
        executeTracers(mFixedPhaseOpportunityTracers,
                       &notice);
    }
    const bool observedExact = !hadTracer ||
        (notice.rendererCallbackObservedNanos >= notice.wakeDispatchNanos &&
         notice.rendererCallbackObservedNanos > 0);
    if (!observedExact) {
        mFatalPacingError.store(true, std::memory_order_release);
    }
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        if (mFixedAvailableCandidate.has_value() &&
            mFixedAvailableCandidate->candidateSequence ==
                notice.candidateSequence &&
            mFixedAvailableCandidate->wakeNotice) {
            *mFixedAvailableCandidate->wakeNotice = notice;
        }
        if (mFixedClaimedCandidate.has_value() &&
            mFixedClaimedCandidate->candidateSequence ==
                notice.candidateSequence &&
            mFixedClaimedCandidate->wakeNotice) {
            *mFixedClaimedCandidate->wakeNotice = notice;
        }
        if (mFixedPublishedOpportunity.has_value() &&
            mFixedPublishedOpportunity->candidateSequence ==
                notice.candidateSequence) {
            mFixedPublishedOpportunity->wakeNotice = notice;
        }
    }
    handoffLock.unlock();
    {
        std::lock_guard<std::mutex> lock(mFixedPhaseTelemetryMutex);
        if (FixedGenerationTelemetryRecord* record =
                findFixedTelemetryLocked(notice.workGeneration)) {
            record->phase.wakeNoticeSequence = notice.noticeSequence;
            record->phase.retirementStageNanos =
                notice.retirementStageNanos;
            record->phase.demandMutationCompleteNanos =
                notice.demandMutationCompleteNanos;
            record->phase.terminalVisibleNanos =
                notice.terminalVisibleNanos;
            record->phase.wakeDispatchNanos = notice.wakeDispatchNanos;
            record->phase.rendererCallbackObservedNanos =
                notice.rendererCallbackObservedNanos;
            record->phase.rendererWakeObservedNanos =
                notice.rendererCallbackObservedNanos;
        }
        (void)retirementWorkGeneration;
    }
}

void SwappyCommon::fixedRetirementCompletedCallbacks(
        const FixedSubmittedRetirement& retired) {
    std::lock_guard<std::mutex> lock(mInjectedTracersMutex);
    SwappyFixedRetirementTelemetryV2 event{};
    event.structSize = sizeof(event);
    event.version = SWAPPY_FIXED_RETIREMENT_TELEMETRY_V2_VERSION;
    event.workGeneration = retired.workGeneration;
    event.ntkFrameId = retired.frameId;
    event.engineGeneration = retired.engineGeneration;
    event.surfaceEpoch = retired.surfaceEpoch;
    event.authorityGeneration = retired.authorityGeneration;
    event.authority = retired.authority;
    event.frameSequence = retired.frameSequence;
    event.admissionSequence = retired.admissionSequence;
    event.capsuleSequence = retired.capsuleSequence;
    event.retirementSequence = retired.retirementSequence;
    event.backendSurfaceSerial = retired.backendSurfaceSerial;
    event.transactionSerial = retired.transactionSerial;
    event.bufferSlot = retired.bufferSlot;
    event.bufferGeneration = retired.bufferGeneration;
    event.frameTimelineVsyncId = retired.frameTimelineVsyncId;
    event.plannedTargetFrame = retired.plannedTargetFrame;
    event.targetReachedNanos = retired.targetReachedNanos;
    event.callbackPublishedNanos = monotonicNowNanos();
    event.targetWaitCount = retired.targetWaitCount;
    event.targetRebaseCount = retired.targetRebaseCount;
    event.state = retired.state == FixedRetirementState::RETIRED
        ? SWAPPY_FIXED_RETIREMENT_RETIRED
        : SWAPPY_FIXED_RETIREMENT_FATAL;
    event.fatalReason = retired.fatalReason;
    executeTracers(mInjectedTracers.fixedRetirementCompleted, &event);
}

void SwappyCommon::setFixedNonPipelineMode(nanoseconds swapDuration) {
    bool valid = false;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        mFixedNonPipelineMode = true;
        mAutoSwapIntervalEnabled = false;
        mPipelineModeAutoMode = false;
        mPipelineMode = PipelineMode::Off;
        mAutoSwapInterval = 1;
        mAutoSwapIntervalThreshold = swapDuration;
        mSwapDuration = swapDuration;
        mNextTimingSettings.swapDuration = swapDuration;
        mTimingSettingsNeedUpdate = true;
        mFramePacingEnabled = true;
        mBlockingWaitEnabled = true;
        // A newly attached fixed-mode surface has no prior window-swap sample. Leaving the
        // Choreographer filter's work estimate at zero publishes the first render opportunity
        // at the cutoff instead of ahead of it. Seed the existing Swappy phase filter with the
        // Oracle's exact submit-feasibility Q=T/2. Fixed mode keeps this phase lead pinned in
        // updateMeasuredSwapDuration; measured returns remain telemetry and never rewrite H/Q/D.
        mMeasuredSwapDuration.store(swapDuration / 2, std::memory_order_release);
        // Fixed mode must never request a per-frame presentation time. Reset
        // any stale request left by the adaptive path before validating T/A/D.
        mPresentationTimeNeeded = false;
        mBufferStuffingFixWait = 0;
        mBufferStuffingFixCounter = 0;
        mMissedFrameCounter = 0;
        mFrameDurations.clear();
        valid = validateFixedConfigurationLocked(swapDuration);
        mFixedPhaseConfigurationValid = valid;
        mFatalPacingError.store(!valid, std::memory_order_release);
    }
    if (!valid) {
        mChoreographerFilter->setFixedInlineDispatch(false);
        SWAPPY_LOGE(
            "FATAL: invalid fixed phase T/A/D or fixed non-pipeline invariant");
        return;
    }
    // Enable the exact raw handoff before rearming Choreographer.  Once this
    // call returns, a predictive worker sleeping across the transition cannot
    // publish delayed authority into fixed mode.
    mChoreographerFilter->setFixedInlineDispatch(true);
    if (!mUsingExternalChoreographer) {
        mChoreographerThread->enterFixedDemandModeForNtk();
        mChoreographerThread->requestFixedFrameCallbackForNtk(
            FIXED_DEMAND_OPPORTUNITY);
    }
}

int32_t SwappyCommon::getPipelineModeForNtk() {
    std::lock_guard<std::mutex> lock(mMutex);
    return mPipelineMode == PipelineMode::Off ? 0 : 1;
}

bool SwappyCommon::isFixedNonPipelineModeForNtk() {
    std::lock_guard<std::mutex> lock(mMutex);
    return mFixedNonPipelineMode;
}

bool SwappyCommon::isBlockingWaitEnabledForNtk() {
    std::lock_guard<std::mutex> lock(mMutex);
    return mBlockingWaitEnabled;
}

void SwappyCommon::setPreferredDisplayModeId(int modeId) {
    if (!mDisplayManager || modeId < 0 || mNextModeId == modeId) {
        return;
    }

    mNextModeId = modeId;
    mDisplayManager->setPreferredDisplayModeId(modeId);
    SWAPPY_LOGV("setPreferredDisplayModeId set to %d", modeId);
}

int SwappyCommon::calculateSwapInterval(nanoseconds frameTime,
                                        nanoseconds refreshPeriod) {
    if (frameTime < refreshPeriod) {
        return 1;
    }

    auto div_result = div(frameTime.count(), refreshPeriod.count());
    auto framesPerRefresh = div_result.quot;
    auto framesPerRefreshRemainder = div_result.rem;

    return (framesPerRefresh +
            (framesPerRefreshRemainder > REFRESH_RATE_MARGIN.count() ? 1 : 0));
}

void SwappyCommon::setPreferredRefreshPeriod(nanoseconds frameTime) {
    if (mANativeWindow_setFrameRate && mWindow) {
        auto frameRate = 1e9f / frameTime.count();

        frameRate = std::min(frameRate, 1e9f / (mSwapDuration).count());
        if (mFixedNonPipelineMode) {
            // NtkStripSurfaceView applies FIXED_SOURCE + CHANGE_FRAME_RATE_ALWAYS before the
            // EGL renderer attaches. The legacy three-argument native API below is equivalent
            // to ONLY_IF_SEAMLESS and would overwrite that exact Surface vote with DEFAULT.
            // Fixed mode therefore preserves the app's stronger vote and owns only frame
            // submission cadence; adaptive Swappy users retain the upstream behavior below.
            mLatestFrameRateVote = frameRate;
            TRACE_INT("preferredRefreshPeriod", (int)frameRate);
            return;
        }
        if (std::abs(mLatestFrameRateVote - frameRate) >
            FRAME_RATE_VOTE_MARGIN) {
            mLatestFrameRateVote = frameRate;
            SWAPPY_LOGV("ANativeWindow_setFrameRate(%.2f)", frameRate);
            mANativeWindow_setFrameRate(
                mWindow, frameRate,
                ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT);
        }

        TRACE_INT("preferredRefreshPeriod", (int)frameRate);
    } else {
        if (!mDisplayManager || !mSupportedRefreshPeriods) {
            return;
        }
        // Loop across all supported refresh periods to find the best refresh
        // period. Best refresh period means:
        //      Shortest swap period that can still accommodate the frame time
        //      and that has the longest refresh period possible to optimize
        //      power consumption.
        std::pair<nanoseconds, int> bestRefreshConfig;
        nanoseconds minSwapDuration = 1s;
        for (const auto& refreshConfig : *mSupportedRefreshPeriods) {
            const auto period = refreshConfig.first;
            const int swapIntervalForPeriod =
                calculateSwapInterval(frameTime, period);
            const nanoseconds swapDuration = period * swapIntervalForPeriod;

            // Don't allow swapping faster than mSwapDuration (see public
            // header)
            if (swapDuration + FRAME_MARGIN < mSwapDuration) {
                continue;
            }

            // We iterate in ascending order of refresh period, so accepting any
            // better or equal-within-margin duration here chooses the longest
            // refresh period possible.
            if (swapDuration < minSwapDuration + FRAME_MARGIN) {
                minSwapDuration = swapDuration;
                bestRefreshConfig = refreshConfig;
            }
        }

        // Switch if we have a potentially better refresh rate
        {
            TRACE_INT("preferredRefreshPeriod",
                      bestRefreshConfig.first.count());
            setPreferredDisplayModeId(bestRefreshConfig.second);
        }
    }
}

void SwappyCommon::onSettingsChanged() {
    std::lock_guard<std::mutex> lock(mMutex);

    TimingSettings timingSettings =
        TimingSettings::from(*Settings::getInstance());

    // If display timings has changed, cache the update and apply them on the
    // next frame
    if (timingSettings != mNextTimingSettings) {
        mNextTimingSettings = timingSettings;
        mTimingSettingsNeedUpdate = true;
    }
}

void SwappyCommon::startFrame() {
    TRACE_CALL();

    std::int64_t currentFrame;
    std::chrono::steady_clock::time_point currentFrameTimestamp;
    std::optional<std::chrono::nanoseconds> sfToVsyncDelay;
    {
        std::unique_lock<std::mutex> lock(mWaitingMutex);
        currentFrame = mCurrentFrame;
        currentFrameTimestamp = mCurrentFrameTimestamp;
        sfToVsyncDelay = mSfToVsyncDelay;
    }

    // Whether to add a wait to fix buffer stuffing.
    bool waitFrame = false;

    const int intervals = (mPipelineMode == PipelineMode::On) ? 2 : 1;

    // Use frame statistics to fix any buffer stuffing
    if (mBufferStuffingFixWait > 0 && mLastLatencyRecorded) {
        int32_t lastLatency = mLastLatencyRecorded();
        int expectedLatency = mAutoSwapInterval * intervals;
        if (sfToVsyncDelay) {
            expectedLatency += *sfToVsyncDelay / mCommonSettings.refreshPeriod;
        }
        TRACE_INT("ExpectedLatency", expectedLatency);
        if (mBufferStuffingFixCounter == 0) {
            if (lastLatency > expectedLatency) {
                mMissedFrameCounter++;
                if (mMissedFrameCounter >= mBufferStuffingFixWait) {
                    waitFrame = true;
                    mBufferStuffingFixCounter = 2 * lastLatency;
                    TRACE_INT("BufferStuffingFix", mBufferStuffingFixCounter);
                }
            } else {
                mMissedFrameCounter = 0;
            }
        } else {
            --mBufferStuffingFixCounter;
            TRACE_INT("BufferStuffingFix", mBufferStuffingFixCounter);
        }
    }
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        mTargetFrame = currentFrame + mAutoSwapInterval + (waitFrame ? 1 : 0);
    }

    // If available, use the SF to Vsync delay to target the specific
    // vsync instead of guessing when the vsync is going to be
    if (sfToVsyncDelay) {
        currentFrameTimestamp += *sfToVsyncDelay -
                                 mCommonSettings.refreshPeriod / 2 -
                                 mMeasuredSwapDuration.load() - 1ms;
    }

    // We compute the target time as now
    //   + the time the buffer will be on the GPU and in the queue to the
    //   compositor (1 swap period)
    mPresentationTime =
        currentFrameTimestamp +
        (mAutoSwapInterval * intervals) * mCommonSettings.refreshPeriod;

    mStartFrameTime = std::chrono::steady_clock::now();
    mCPUTracer.startTrace();

    startFrameCallbacks();
}

void SwappyCommon::waitUntil(std::int64_t target) {
    TRACE_CALL();
    std::unique_lock<std::mutex> lock(mWaitingMutex);
    mWaitingCondition.wait(lock, [&]() {
        if (mCurrentFrame < target) {
            if (!mUsingExternalChoreographer) {
                mChoreographerThread->postFrameCallbacks();
            }
            return false;
        }
        return true;
    });
}

void SwappyCommon::finishFixedFrameStatistics(
    FixedSubmittedRetirement& retired) {
    // Do not call stock startFrame(), addFrameDuration(), or touch
    // mStartFrameTime here: the producer may already be drawing g+1.  The
    // generation-bound retirement sidecar is the statistic of record; this
    // tracer remains a diagnostic notification only.
    publishFixedRetirementTerminalOnce(retired);
}

void SwappyCommon::publishFixedRetirementTerminalOnce(
        FixedSubmittedRetirement& retirement) {
    const bool terminal = retirement.state == FixedRetirementState::RETIRED ||
        retirement.state == FixedRetirementState::FATAL;
    if (!terminal || !retirement.terminalPublicationComplete ||
        retirement.retirementCallbackPublished) {
        return;
    }
    if (retirement.retirementCallbackPublishCount != 1) {
        retirement.state = FixedRetirementState::FATAL;
        if (retirement.fatalReason == 0) {
            retirement.fatalReason = static_cast<std::int32_t>(
                FixedPhaseFatalReason::SUBMISSION_FAILED);
        }
        return;
    }
    retirement.retirementCallbackPublished = true;
    fixedRetirementCompletedCallbacks(retirement);
    std::lock_guard<std::mutex> lock(mWaitingMutex);
    if (mFixedSubmittedRetirement.has_value() &&
        mFixedSubmittedRetirement->workGeneration ==
            retirement.workGeneration &&
        mFixedSubmittedRetirement->retirementSequence ==
            retirement.retirementSequence) {
        mFixedSubmittedRetirement->retirementCallbackPublished = true;
        mFixedSubmittedRetirement->retirementCallbackPublishCount =
            retirement.retirementCallbackPublishCount;
    }
}

void SwappyCommon::waitUntilTargetFrame() {
    std::int64_t target = 0;
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        target = mTargetFrame;
    }
    waitUntil(target);
}

void SwappyCommon::waitOneFrame() { waitUntil(mCurrentFrame + 1); }

SdkVersion SwappyCommonSettings::getSDKVersion(JNIEnv* env) {
    const jclass buildClass = env->FindClass("android/os/Build$VERSION");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        SWAPPY_LOGE("Failed to get Build.VERSION class");
        return SdkVersion{0, 0};
    }

    const jfieldID sdkInt = env->GetStaticFieldID(buildClass, "SDK_INT", "I");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        SWAPPY_LOGE("Failed to get Build.VERSION.SDK_INT field");
        return SdkVersion{0, 0};
    }

    const jint sdk = env->GetStaticIntField(buildClass, sdkInt);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        SWAPPY_LOGE("Failed to get SDK version");
        return SdkVersion{0, 0};
    }

    jint sdkPreview = 0;
    if (sdk >= 23) {
        const jfieldID previewSdkInt =
            env->GetStaticFieldID(buildClass, "PREVIEW_SDK_INT", "I");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            SWAPPY_LOGE("Failed to get Build.VERSION.PREVIEW_SDK_INT field");
        }

        sdkPreview = env->GetStaticIntField(buildClass, previewSdkInt);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            SWAPPY_LOGE("Failed to get preview SDK version");
        }
    }

    SWAPPY_LOGI("SDK version = %d preview = %d", sdk, sdkPreview);
    return SdkVersion{sdk, sdkPreview};
}

void SwappyCommon::setANativeWindow(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mWindow == window) {
        return;
    }

    if (mWindow != nullptr) {
        ANativeWindow_release(mWindow);
    }

    mWindow = window;
    if (mWindow != nullptr) {
        ANativeWindow_acquire(mWindow);
        mWindowChanged = true;
        mLatestFrameRateVote = 0;
    }
}

namespace {

static std::string GetStaticStringField(JNIEnv* env, jclass clz,
                                        const char* name) {
    const jfieldID fieldId =
        env->GetStaticFieldID(clz, name, "Ljava/lang/String;");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        SWAPPY_LOGE("Failed to get string field %s", name);
        return "";
    }

    const jstring jstr = (jstring)env->GetStaticObjectField(clz, fieldId);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        SWAPPY_LOGE("Failed to get string %s", name);
        return "";
    }
    auto cstr = env->GetStringUTFChars(jstr, nullptr);
    auto length = env->GetStringUTFLength(jstr);
    std::string retValue(cstr, length);
    env->ReleaseStringUTFChars(jstr, cstr);
    env->DeleteLocalRef(jstr);
    return retValue;
}

struct DeviceIdentifier {
    std::string manufacturer;
    std::string model;
    std::string display;
    // Empty fields match against any value and we match the beginning of the
    // input, e.g.
    //  A37 matches A37f, A37fw, etc.
    bool match(const std::string& manufacturer_in, const std::string& model_in,
               const std::string& display_in) {
        if (!matchStartOfString(manufacturer, manufacturer_in)) return false;
        if (!matchStartOfString(model, model_in)) return false;
        if (!matchStartOfString(display, display_in)) return false;
        return true;
    }
    bool matchStartOfString(const std::string& start,
                            const std::string& sample) {
        return start.empty() || start == sample.substr(0, start.length());
    }
};

}  // anonymous namespace

bool SwappyCommon::isDeviceUnsupported() {
    JNIEnv* env;
    mJVM->AttachCurrentThread(&env, nullptr);

    // List of unsupported models
    static std::vector<DeviceIdentifier> unsupportedDevices = {
        {"OPPO", "A37", ""}};

    const jclass buildClass = env->FindClass("android/os/Build");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        SWAPPY_LOGE("Failed to get Build class");
        return false;
    }

    auto manufacturer = GetStaticStringField(env, buildClass, "MANUFACTURER");
    if (manufacturer.empty()) return false;

    auto model = GetStaticStringField(env, buildClass, "MODEL");
    if (model.empty()) return false;

    auto display = GetStaticStringField(env, buildClass, "DISPLAY");
    if (display.empty()) return false;

    for (auto& device : unsupportedDevices) {
        if (device.match(manufacturer, model, display)) return true;
    }

    return false;
}

int SwappyCommon::getSupportedRefreshPeriodsNS(uint64_t* out_refreshrates,
                                               int allocated_entries) {
    if (mDisplayManager) {
        mSupportedRefreshPeriods =
            mDisplayManager->getSupportedRefreshPeriods();
    }

    if (!mSupportedRefreshPeriods) return 0;
    if (!out_refreshrates) return (*mSupportedRefreshPeriods).size();

    int counter = 0;
    for (const auto& pair : *mSupportedRefreshPeriods) {
        out_refreshrates[counter] = pair.first.count();
        ++counter;
    }

    return (*mSupportedRefreshPeriods).size();
}

void SwappyCommon::resetFramePacing() {
    std::lock_guard<std::mutex> lock(mMutex);

    // Just set the flag here, we actually reset at the end of the frame.
    mFramePacingResetRequested = true;
}

void SwappyCommon::enableFramePacing(bool enable) {
    std::lock_guard<std::mutex> lock(mMutex);

    // Set flags that are applied at the end of the frame.
    if (mFramePacingEnabled != enable) {
        mFramePacingToggleRequested = true;
        mFramePacingResetRequested = true;
    }
}

void SwappyCommon::enableBlockingWait(bool enable) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mFixedNonPipelineMode && !enable) {
        SWAPPY_LOGE("Cannot disable blocking wait in fixed non-pipeline mode");
        mFatalPacingError.store(true, std::memory_order_release);
        return;
    }
    mBlockingWaitEnabled = enable;
}

}  // namespace swappy
