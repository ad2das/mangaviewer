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

#include "SwappyGL.h"
#define LOG_TAG "SwappyGL"

#include <cinttypes>
#include <cmath>
#include <cstdlib>

#include "SwappyLog.h"
#include "FixedExternalSubmissionContract.h"
#include "Thread.h"
#include "Trace.h"
#include "system_utils.h"

namespace swappy {

using std::chrono::milliseconds;
using std::chrono::nanoseconds;

namespace {

std::int64_t monotonicNowNanos() noexcept {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

SwappyFixedCommitStatus toCommitStatus(FixedPhaseAdmissionStatus status) {
    switch (status) {
        case FixedPhaseAdmissionStatus::ADMITTED:
            return SWAPPY_FIXED_COMMIT_SUBMITTED;
        case FixedPhaseAdmissionStatus::WAITING_PRIOR_TARGET:
            return SWAPPY_FIXED_COMMIT_WAITING_PRIOR_TARGET;
        case FixedPhaseAdmissionStatus::WAITING_CANDIDATE:
            return SWAPPY_FIXED_COMMIT_WAITING_CANDIDATE;
        case FixedPhaseAdmissionStatus::WAITING_PRIOR_LATCH:
            return SWAPPY_FIXED_COMMIT_WAITING_PRIOR_LATCH;
        case FixedPhaseAdmissionStatus::SLOT_CLOSED_WAITING_NEXT:
            return SWAPPY_FIXED_COMMIT_SLOT_CLOSED_WAITING_NEXT;
        case FixedPhaseAdmissionStatus::FATAL:
            return SWAPPY_FIXED_COMMIT_FATAL;
    }
    return SWAPPY_FIXED_COMMIT_FATAL;
}

}  // namespace

std::mutex SwappyGL::sInstanceMutex;
std::unique_ptr<SwappyGL> SwappyGL::sInstance;

bool SwappyGL::init(JNIEnv *env, jobject jactivity) {
    std::lock_guard<std::mutex> lock(sInstanceMutex);
    if (sInstance) {
        SWAPPY_LOGE("Attempted to initialize SwappyGL twice");
        return false;
    }
    sInstance = std::make_unique<SwappyGL>(env, jactivity, ConstructorTag{});
    if (!sInstance->mEnableSwappy) {
        SWAPPY_LOGE("Failed to initialize SwappyGL");
        return false;
    }

    return true;
}

void SwappyGL::onChoreographer(int64_t frameTimeNanos) {
    TRACE_CALL();

    SwappyGL *swappy = getInstance();
    if (!swappy) {
        return;
    }

    swappy->mCommonBase.onChoreographer(frameTimeNanos);
}

bool SwappyGL::setWindow(ANativeWindow *window) {
    TRACE_CALL();

    SwappyGL *swappy = getInstance();
    if (!swappy) {
        SWAPPY_LOGE("Failed to get SwappyGL instance in setWindow");
        return false;
    }

    swappy->mCommonBase.setANativeWindow(window);
    return true;
}

bool SwappyGL::swap(EGLDisplay display, EGLSurface surface) {
    TRACE_CALL();

    SwappyGL *swappy = getInstance();
    if (!swappy) {
        return EGL_FALSE;
    }

    if (swappy->enabled()) {
        return swappy->swapInternal(display, surface);
    } else {
        return swappy->getEgl()->swapBuffers(display, surface) == EGL_TRUE;
    }
}



bool SwappyGL::reserveFixedFrameForNtk(
    std::uint64_t workGeneration,
    SwappyFixedReservationReceipt* outReceipt) {
    if (outReceipt) *outReceipt = {};
    SwappyGL *swappy = getInstance();
    if (!swappy || !swappy->enabled() || workGeneration == 0 ||
        outReceipt == nullptr ||
        !swappy->mCommonBase.isFixedNonPipelineModeForNtk()) {
        return false;
    }
    FixedReservationReceipt receipt{};
    const FixedPhaseAdmissionStatus reservation =
        swappy->mCommonBase.reserveFixedFrameForNtk(
            workGeneration, &receipt);
    if (reservation != FixedPhaseAdmissionStatus::ADMITTED) return false;
    outReceipt->structSize = sizeof(*outReceipt);
    outReceipt->version = SWAPPY_FIXED_RESERVATION_RECEIPT_VERSION;
    outReceipt->workGeneration = receipt.workGeneration;
    outReceipt->reservationSequence = receipt.reservationSequence;
    outReceipt->rawBaselineSequence = receipt.rawBaselineSequence;
    outReceipt->reservationNanos = receipt.reservationNanos;
    return true;
}


bool SwappyGL::abortFixedReservationForNtk(
    std::uint64_t workGeneration) {
    SwappyGL *swappy = getInstance();
    if (!swappy || !swappy->enabled() || workGeneration == 0) return false;
    if (!swappy->mCommonBase.abortPreparedFixedFrameForNtk(workGeneration)) {
        return false;
    }
    std::lock_guard<std::mutex> lock(swappy->mExternalSubmissionMutex);
    if (swappy->mExternalPreparedWorkGeneration.has_value() &&
        *swappy->mExternalPreparedWorkGeneration == workGeneration) {
        swappy->mExternalPreparedWorkGeneration.reset();
    }
    return true;
}

bool SwappyGL::markReservedExternalGpuReadyForNtk(
        std::uint64_t workGeneration,
        const SwappyFixedExternalTransportReady* transportReady) {
    SwappyGL* swappy = getInstance();
    if (!swappy || !swappy->enabled() || !transportReady ||
        workGeneration == 0 ||
        !fixedExternalTransportReadyValid(*transportReady) ||
        transportReady->workGeneration != workGeneration) {
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(swappy->mExternalSubmissionMutex);
        if (swappy->mExternalClaim.has_value() ||
            swappy->mExternalPreparedWorkGeneration.has_value()) {
            return false;
        }
        // Install before Common can synchronously dispatch a carried JOIN_OPEN.
        swappy->mExternalPreparedWorkGeneration = workGeneration;
    }
    if (swappy->mCommonBase.markReservedExternalGpuReadyForNtk(
            workGeneration, *transportReady)) {
        return true;
    }
    std::lock_guard<std::mutex> lock(swappy->mExternalSubmissionMutex);
    if (swappy->mExternalPreparedWorkGeneration.has_value() &&
        *swappy->mExternalPreparedWorkGeneration == workGeneration) {
        swappy->mExternalPreparedWorkGeneration.reset();
    }
    return false;
}


SwappyFixedCommitStatus
SwappyGL::claimPreparedExternalFixedFrameForNtk(
        const SwappyFixedOpportunityIdentity* expectedOpportunity,
        const SwappyFixedExternalTransportReady* transportReady,
        SwappyFixedExternalClaim* outClaim) {
    if (outClaim) *outClaim = {};
    SwappyGL* swappy = getInstance();
    if (!swappy || !swappy->enabled() || !outClaim || !transportReady ||
        !expectedOpportunity ||
        expectedOpportunity->structSize != sizeof(*expectedOpportunity) ||
        expectedOpportunity->version !=
            SWAPPY_FIXED_OPPORTUNITY_IDENTITY_VERSION ||
        expectedOpportunity->workGeneration == 0 ||
        expectedOpportunity->reservationSequence == 0 ||
        expectedOpportunity->opportunitySequence == 0 ||
        expectedOpportunity->candidateSequence == 0 ||
        expectedOpportunity->noticeSequence == 0) {
        return SWAPPY_FIXED_COMMIT_FATAL;
    }
    return swappy->claimPreparedExternalFixedFrameInternal(
        *expectedOpportunity, *transportReady, outClaim);
}

bool SwappyGL::recordExternalLatchObservationForNtk(
        const SwappyFixedLatchObservationV1* observation) {
    SwappyGL* swappy = getInstance();
    return swappy && swappy->enabled() && observation &&
        swappy->mCommonBase.recordExternalLatchObservationForNtk(
            *observation);
}

SwappyFixedCommitStatus
SwappyGL::claimPreparedExternalFixedFrameInternal(
        const SwappyFixedOpportunityIdentity& expectedOpportunity,
        const SwappyFixedExternalTransportReady& transportReady,
        SwappyFixedExternalClaim* outClaim) {
    const std::uint64_t workGeneration = expectedOpportunity.workGeneration;
    if (!fixedExternalTransportReadyValid(transportReady) ||
        transportReady.workGeneration != workGeneration) {
        mCommonBase.markFixedPhaseSubmissionFailureForNtk();
        return SWAPPY_FIXED_COMMIT_FATAL;
    }
    {
        std::lock_guard<std::mutex> lock(mExternalSubmissionMutex);
        if (mExternalClaim.has_value()) return SWAPPY_FIXED_COMMIT_FATAL;
        if (!mExternalPreparedWorkGeneration.has_value() ||
            *mExternalPreparedWorkGeneration != workGeneration) {
            return SWAPPY_FIXED_COMMIT_FATAL;
        }
    }

    FixedOpportunityIdentity expected{};
    expected.workGeneration = expectedOpportunity.workGeneration;
    expected.reservationSequence = expectedOpportunity.reservationSequence;
    expected.opportunitySequence = expectedOpportunity.opportunitySequence;
    expected.candidateSequence = expectedOpportunity.candidateSequence;
    expected.noticeSequence = expectedOpportunity.noticeSequence;
    FixedPhaseAdmissionToken token{};
    const FixedPhaseAdmissionStatus status =
        mCommonBase.commitPreparedFixedFrameForNtk(
            expected, &token, transportReady);
    if (status != FixedPhaseAdmissionStatus::ADMITTED) {
        return toCommitStatus(status);
    }
    SwappyFixedExternalClaim claim{};
    claim.structSize = sizeof(claim);
    claim.version = SWAPPY_FIXED_EXTERNAL_CLAIM_VERSION;
    claim.claimToken = token.sequence;
    claim.workGeneration = token.workGeneration;
    claim.admissionSequence = token.sequence;
    claim.reservationSequence = token.reservationSequence;
    claim.opportunitySequence = token.opportunitySequence;
    claim.candidateSequence = token.candidateSequence;
    claim.noticeSequence = token.joinNotice.noticeSequence;
    claim.plannedTargetFrame = token.plan.plannedTargetFrame;
    claim.frameTimelineVsyncId = token.raw.frameTimelineVsyncId;
    claim.decisionNanos = token.input.decisionNanos;
    claim.ntkFrameId = transportReady.ntkFrameId;
    claim.engineGeneration = transportReady.engineGeneration;
    claim.surfaceEpoch = transportReady.surfaceEpoch;
    claim.authorityGeneration = transportReady.authorityGeneration;
    claim.authority = transportReady.authority;
    claim.frameSequence = transportReady.frameSequence;
    claim.capsuleSequence = transportReady.capsuleSequence;
    claim.backendSurfaceSerial = transportReady.backendSurfaceSerial;
    claim.transactionSerial = transportReady.transactionSerial;
    claim.bufferSlot = transportReady.bufferSlot;
    claim.bufferGeneration = transportReady.bufferGeneration;
    claim.acquireFenceSerial = transportReady.acquireFenceSerial;
    claim.transportProfileDigest = transportReady.profile.profileDigest;
    claim.timingGeneration = transportReady.profile.timingGeneration;
    claim.transportBoundNanos = transportReady.profile.transportBoundNanos;
    claim.prepareBeginNanos = transportReady.prepareBeginNanos;
    claim.prepareEndNanos = transportReady.prepareEndNanos;
    claim.initialDecisionNanos = token.initialDecisionNanos;
    claim.transportAdmissionOutcome = static_cast<std::int32_t>(
        token.transportAdmission.outcome);
    claim.setBufferCount = transportReady.setBufferCount;
    claim.acquireFenceDupCount = transportReady.acquireFenceDupCount;
    claim.setBufferPending = transportReady.setBufferPending;
    claim.firstStage = transportReady.firstStage;
    claim.priorLatchGateRequired =
        token.priorRetirementProof.hasPrior == 1 ? 1U : 0U;
    claim.priorLatchGateUsed =
        token.priorRetirementProof.hasPrior == 1 ? 1U : 0U;
    claim.priorCommitProofPendingAtClaim =
        token.priorCommitProofPendingAtClaim ? 1U : 0U;
    claim.priorLatchObservation = token.priorLatchObservedAtClaim
        ? token.priorLatchObservation
        : SwappyFixedLatchObservationV1{};
    claim.priorRetirementProof = token.priorRetirementProof;
    claim.previousAppliedBufferRef =
        transportReady.previousAppliedBufferRef;
    if (claim.claimToken == 0 || claim.frameTimelineVsyncId == 0 ||
        claim.plannedTargetFrame <= 0 ||
        claim.prepareEndNanos > claim.initialDecisionNanos ||
        (claim.priorRetirementProof.hasPrior == 1
            ? (!fixedPriorRetirementProofValid(
                   claim.priorRetirementProof) ||
               !fixedAppliedBufferRefExact(
                   claim.priorRetirementProof.predecessor,
                   claim.previousAppliedBufferRef) ||
               claim.priorRetirementProof.retirementCompleteNanos >
                   claim.initialDecisionNanos ||
               claim.priorLatchGateRequired != 1 ||
               claim.priorLatchGateUsed != 1 ||
               claim.priorCommitProofPendingAtClaim != 0 ||
               !fixedLatchObservationValid(
                   claim.priorLatchObservation) ||
               !fixedFrameIdentityExact(
                   claim.priorLatchObservation.identity,
                   claim.previousAppliedBufferRef.identity) ||
               claim.priorLatchObservation.callbackObservedNanos >
                   claim.initialDecisionNanos ||
               claim.firstStage != 0)
            : (!fixedPriorRetirementProofEmpty(
                   claim.priorRetirementProof) ||
               !fixedAppliedBufferRefEmpty(
                   claim.previousAppliedBufferRef) ||
               claim.priorLatchGateRequired != 0 ||
               claim.priorLatchGateUsed != 0 ||
               claim.priorCommitProofPendingAtClaim != 0 ||
               !fixedLatchObservationEmpty(
                   claim.priorLatchObservation) ||
               claim.firstStage != 1))) {
        mCommonBase.markFixedPhaseSubmissionFailureForNtk();
        return SWAPPY_FIXED_COMMIT_FATAL;
    }
    claim.claimReturnNanos = SwappyCommon::externalClaimClockNowNanos();
    if (claim.claimReturnNanos < claim.decisionNanos) {
        (void)mCommonBase.abortClaimedExternalFixedFrameForNtk(
            workGeneration);
        mCommonBase.markFixedPhaseSubmissionFailureForNtk();
        return SWAPPY_FIXED_COMMIT_FATAL;
    }
    {
        std::lock_guard<std::mutex> lock(mExternalSubmissionMutex);
        if (mExternalClaim.has_value()) {
            (void)mCommonBase.abortClaimedExternalFixedFrameForNtk(
                workGeneration);
            mCommonBase.markFixedPhaseSubmissionFailureForNtk();
            return SWAPPY_FIXED_COMMIT_FATAL;
        }
        mExternalClaim = ExternalClaimState{token, claim};
    }
    *outClaim = claim;
    return SWAPPY_FIXED_COMMIT_SUBMITTED;
}

bool SwappyGL::commitExternalFixedSubmissionForNtk(
        const SwappyFixedExternalClaim* claim,
        const SwappyFixedExternalSubmission* submission,
        SwappyFixedExternalSubmissionReceipt* outReceipt) {
    if (outReceipt) *outReceipt = {};
    SwappyGL* swappy = getInstance();
    if (!swappy || !swappy->enabled() || !claim || !submission || !outReceipt) {
        return false;
    }
    return swappy->commitExternalFixedSubmissionInternal(
        *claim, *submission, outReceipt);
}

bool SwappyGL::commitExternalFixedSubmissionInternal(
        const SwappyFixedExternalClaim& claim,
        const SwappyFixedExternalSubmission& submission,
        SwappyFixedExternalSubmissionReceipt* outReceipt) {
    ExternalClaimState state{};
    {
        std::lock_guard<std::mutex> lock(mExternalSubmissionMutex);
        if (!mExternalClaim.has_value() ||
            mExternalClaim->claim.claimToken != claim.claimToken) {
            SWAPPY_LOGE(
                "FATAL: external submission claim missing token=%llu current=%llu",
                static_cast<unsigned long long>(claim.claimToken),
                static_cast<unsigned long long>(
                    mExternalClaim.has_value()
                        ? mExternalClaim->claim.claimToken : 0));
            return false;
        }
        state = *mExternalClaim;
    }
    const bool exactClaim = fixedExternalClaimExact(claim, state.claim);
    const bool exactSubmission = fixedExternalSubmissionExact(claim, submission);
    if (!exactClaim || !exactSubmission) {
        SWAPPY_LOGE(
            "FATAL: external submission identity mismatch claim=%d submission=%d "
            "token=%llu work=%llu frame=%llu",
            exactClaim ? 1 : 0, exactSubmission ? 1 : 0,
            static_cast<unsigned long long>(claim.claimToken),
            static_cast<unsigned long long>(submission.workGeneration),
            static_cast<unsigned long long>(submission.ntkFrameId));
    }

    FixedPostSwapStamp stamp{};
    stamp.finalCorridorBeginNanos = claim.decisionNanos;
    stamp.decisionNanos = claim.decisionNanos;
    stamp.queueMarkNanos = submission.transactionApplyBeginNanos;
    stamp.eglSwapEnterNanos = submission.transactionApplyBeginNanos;
    stamp.postSwapNanos = submission.transactionApplyEndNanos;
    const FixedPhaseRuntimeValidation validation =
        validateFixedNonPipelinePostSwap(
            state.token.plan, claim.decisionNanos, stamp.postSwapNanos);
    stamp.phaseValid = validation.valid;
    stamp.outcome = static_cast<std::int32_t>(validation.outcome);
    if (!validation.valid) {
        stamp.fatalReason = static_cast<std::int32_t>(
            validation.outcome ==
                    FixedPhaseRuntimeOutcome::FATAL_SWAP_MISSED_CUTOFF
                ? SwappyCommon::FixedPhaseFatalReason::SWAP_MISSED_CUTOFF
                : SwappyCommon::FixedPhaseFatalReason::SWAP_DURATION_INVALID);
    }

    SwappyFixedPhaseTelemetry phase{};
    std::uint64_t retirementSequence = 0;
    const bool armed = mCommonBase.finishFixedPostSwapForNtk(
        state.token, stamp, &phase, claim, submission, &retirementSequence);

    outReceipt->structSize = sizeof(*outReceipt);
    outReceipt->version = SWAPPY_FIXED_EXTERNAL_RECEIPT_VERSION;
    outReceipt->claim = claim;
    outReceipt->submission = submission;
    outReceipt->phase = phase;
    outReceipt->priorWorkGeneration = state.token.priorRetirementWorkGeneration;
    outReceipt->priorAdmissionSequence =
        state.token.priorRetirementAdmissionSequence;
    outReceipt->priorRetirementSequence = state.token.priorRetirementSequence;
    outReceipt->retirementSequence = retirementSequence;
    outReceipt->applyDisposition = submission.applyDisposition;
    outReceipt->fatalReason = phase.fatalReason != 0
        ? phase.fatalReason
        : phase.retirementFatalReason;
    outReceipt->retirementFatalReason = phase.retirementFatalReason;
    {
        std::lock_guard<std::mutex> lock(mExternalSubmissionMutex);
        mExternalClaim.reset();
        mExternalPreparedWorkGeneration.reset();
    }
    if (retirementSequence == 0) {
        outReceipt->fatalReason = static_cast<std::int32_t>(
            SwappyCommon::FixedPhaseFatalReason::CONSERVATION_FAILURE);
        mCommonBase.markFixedPhaseSubmissionFailureForNtk();
    }
    outReceipt->phase.receiptFatalReason = outReceipt->fatalReason;
    const bool committed = armed && stamp.phaseValid && exactClaim &&
        exactSubmission && retirementSequence != 0 &&
        outReceipt->fatalReason == 0;
    if (!committed) {
        SWAPPY_LOGE(
            "FATAL: external submission phase commit armed=%d valid=%d outcome=%d "
            "fatal=%d work=%llu decision=%lld apply=%lld..%lld cutoff=%lld "
            "latestStart=%lld duration=%lld retirement=%llu",
            armed ? 1 : 0, stamp.phaseValid ? 1 : 0, stamp.outcome,
            stamp.fatalReason,
            static_cast<unsigned long long>(submission.workGeneration),
            static_cast<long long>(claim.decisionNanos),
            static_cast<long long>(submission.transactionApplyBeginNanos),
            static_cast<long long>(submission.transactionApplyEndNanos),
            static_cast<long long>(state.token.plan.plannedCutoffNanos),
            static_cast<long long>(
                state.token.plan.latestSwapStartExclusiveNanos),
            static_cast<long long>(validation.swapDurationNanos),
            static_cast<unsigned long long>(retirementSequence));
    }
    return committed;
}

bool SwappyGL::abortExternalFixedClaimForNtk(std::uint64_t claimToken) {
    SwappyGL* swappy = getInstance();
    if (!swappy || !swappy->enabled() || claimToken == 0) return false;
    std::uint64_t workGeneration = 0;
    {
        std::lock_guard<std::mutex> lock(swappy->mExternalSubmissionMutex);
        if (!swappy->mExternalClaim.has_value() ||
            swappy->mExternalClaim->claim.claimToken != claimToken) {
            return false;
        }
        workGeneration = swappy->mExternalClaim->claim.workGeneration;
    }
    if (!swappy->mCommonBase.abortClaimedExternalFixedFrameForNtk(
            workGeneration)) {
        return false;
    }
    std::lock_guard<std::mutex> lock(swappy->mExternalSubmissionMutex);
    swappy->mExternalClaim.reset();
    swappy->mExternalPreparedWorkGeneration.reset();
    return true;
}

bool SwappyGL::hasExternalFixedClaimForNtk() {
    SwappyGL* swappy = getInstance();
    if (!swappy || !swappy->enabled()) return false;
    std::lock_guard<std::mutex> lock(swappy->mExternalSubmissionMutex);
    return swappy->mExternalClaim.has_value();
}

bool SwappyGL::markFixedPhaseSubmissionFailureForNtk() {
    SwappyGL *swappy = getInstance();
    if (!swappy || !swappy->enabled()) return false;
    swappy->mCommonBase.markFixedPhaseSubmissionFailureForNtk();
    return true;
}

bool SwappyGL::lastFrameIsComplete(EGLDisplay display) {
    bool pipelineMode = (mCommonBase.getCurrentPipelineMode() ==
                         SwappyCommon::PipelineMode::On);
    if (!getEgl()->lastFrameIsComplete(display, pipelineMode)) {
        gamesdk::ScopedTrace trace("lastFrameIncomplete");
        SWAPPY_LOGV("lastFrameIncomplete");
        return false;
    }
    return true;
}

bool SwappyGL::swapInternal(EGLDisplay display, EGLSurface surface) {
    const SwappyCommon::SwapHandlers handlers = {
        .lastFrameIsComplete = [&]() { return lastFrameIsComplete(display); },
        .waitForCurrentFrameCompletion = {},
        .getPrevFrameGpuTime =
            [&]() { return getEgl()->getFencePendingTime(); },
    };

    const bool fixed = mCommonBase.isFixedNonPipelineModeForNtk();
    if (fixed) {
        // The fixed production path is prepare + commit only. Generic swap can
        // never manufacture a fence, token or fallback submission.
        mCommonBase.markFixedPhaseSubmissionFailureForNtk();
        return false;
    }
    if (!getEgl()->insertSyncFence(display)) {
        return false;
    }

    if (!mCommonBase.onPreSwap(handlers)) {
        // A fixed phase fatal is terminal for this frame: do not swap and do
        // not retarget.  The caller owns renderer teardown.
        return false;
    }

    const bool presentationTimeRequested =
        mCommonBase.needToSetPresentationTime();
    if (fixed && presentationTimeRequested) {
        SWAPPY_LOGE(
            "FATAL: fixed non-pipeline mode requested explicit presentation time");
        mCommonBase.markFixedPhaseSubmissionFailureForNtk();
        return false;
    }
    if (!fixed && presentationTimeRequested) {
        const bool setPresentationTimeResult = setPresentationTime(display, surface);
        if (!setPresentationTimeResult) {
            return false;
        }
    }

    const bool swapBuffersResult =
        (getEgl()->swapBuffers(display, surface) == EGL_TRUE);
    if (fixed && !swapBuffersResult) {
        mCommonBase.markFixedPhaseSubmissionFailureForNtk();
        // EGL did not accept the buffer.  Do not enter post-swap phase
        // validation, target waiting, or the current-frame fence/latch path;
        // renderer teardown owns cleanup after this sticky transport fatal.
        return false;
    }

    const bool pacingResult = mCommonBase.onPostSwap(handlers);

    return swapBuffersResult && pacingResult;
}


void SwappyGL::addTracer(const SwappyTracer *tracer) {
    SwappyGL *swappy = getInstance();
    if (!swappy) {
        return;
    }
    if (swappy->enabled() && tracer != nullptr)
        swappy->mCommonBase.addTracerCallbacks(*tracer);
}

void SwappyGL::removeTracer(const SwappyTracer *tracer) {
    SwappyGL *swappy = getInstance();
    if (!swappy) {
        return;
    }
    if (swappy->enabled() && tracer != nullptr)
        swappy->mCommonBase.removeTracerCallbacks(*tracer);
}

nanoseconds SwappyGL::getSwapDuration() {
    SwappyGL *swappy = getInstance();
    if (!swappy || !swappy->enabled()) {
        return -1ns;
    }
    return swappy->mCommonBase.getSwapDuration();
};

void SwappyGL::setAutoSwapInterval(bool enabled) {
    SwappyGL *swappy = getInstance();
    if (!swappy) {
        return;
    }
    if (swappy->enabled()) swappy->mCommonBase.setAutoSwapInterval(enabled);
}

void SwappyGL::setAutoPipelineMode(bool enabled) {
    SwappyGL *swappy = getInstance();
    if (!swappy) {
        return;
    }
    if (swappy->enabled()) swappy->mCommonBase.setAutoPipelineMode(enabled);
}

void SwappyGL::setFixedNonPipelineMode(std::chrono::nanoseconds swapDuration) {
    SwappyGL *swappy = getInstance();
    if (!swappy || !swappy->enabled()) return;
    swappy->mCommonBase.setFixedNonPipelineMode(swapDuration);
}

int32_t SwappyGL::getPipelineModeForNtk() {
    SwappyGL *swappy = getInstance();
    if (!swappy || !swappy->enabled()) return -1;
    return swappy->mCommonBase.getPipelineModeForNtk();
}

bool SwappyGL::isFixedNonPipelineModeForNtk() {
    SwappyGL *swappy = getInstance();
    return swappy != nullptr && swappy->enabled() &&
        swappy->mCommonBase.isFixedNonPipelineModeForNtk();
}

bool SwappyGL::isBlockingWaitEnabledForNtk() {
    SwappyGL *swappy = getInstance();
    return swappy != nullptr && swappy->enabled() &&
        swappy->mCommonBase.isBlockingWaitEnabledForNtk();
}

bool SwappyGL::isFixedPhaseConfigurationValidForNtk() {
    SwappyGL *swappy = getInstance();
    return swappy != nullptr && swappy->enabled() &&
        swappy->mCommonBase.isFixedPhaseConfigurationValidForNtk();
}

bool SwappyGL::getFixedPhaseTelemetryForNtk(
    std::uint64_t workGeneration, SwappyFixedPhaseTelemetry *output) {
    SwappyGL *swappy = getInstance();
    return swappy != nullptr && swappy->enabled() &&
        swappy->mCommonBase.getFixedPhaseTelemetryForNtk(
            workGeneration, output);
}



bool SwappyGL::planFixedPhaseForTesting(
    const SwappyFixedPhasePlanInput *input,
    SwappyFixedPhaseTelemetry *output) {
    return SwappyCommon::planFixedPhaseForTesting(input, output);
}

bool SwappyGL::hasFatalPacingErrorForNtk() {
    SwappyGL *swappy = getInstance();
    return swappy == nullptr || !swappy->enabled() ||
        swappy->mCommonBase.hasFatalPacingErrorForNtk();
}

void SwappyGL::setMaxAutoSwapDuration(std::chrono::nanoseconds maxDuration) {
    SwappyGL *swappy = getInstance();
    if (!swappy) {
        return;
    }
    if (swappy->enabled())
        swappy->mCommonBase.setMaxAutoSwapDuration(maxDuration);
}

void SwappyGL::enableStats(bool enabled) {
    (void)enabled;
}

void SwappyGL::recordFrameStart(EGLDisplay display, EGLSurface surface) {
    (void)display;
    (void)surface;
}

void SwappyGL::getStats(SwappyStats *stats) {
    if (stats != nullptr) *stats = {};
}

void SwappyGL::clearStats() {}

SwappyGL *SwappyGL::getInstance() {
    std::lock_guard<std::mutex> lock(sInstanceMutex);
    return sInstance.get();
}

bool SwappyGL::isEnabled() {
    SwappyGL *swappy = getInstance();
    if (!swappy) {
        // This is a case of error.
        // We do not log anything here, so that we do not spam
        // the user when this function is called each frame.
        return false;
    }
    return swappy->enabled();
}

void SwappyGL::destroyInstance() {
    std::lock_guard<std::mutex> lock(sInstanceMutex);
    sInstance.reset();
}

void SwappyGL::setFenceTimeout(std::chrono::nanoseconds t) {
    SwappyGL *swappy = getInstance();
    if (!swappy || !swappy->enabled()) {
        return;
    }
    swappy->mCommonBase.setFenceTimeout(t);
}

std::chrono::nanoseconds SwappyGL::getFenceTimeout() {
    SwappyGL *swappy = getInstance();
    if (!swappy || !swappy->enabled()) {
        return std::chrono::nanoseconds(0);
    }
    return swappy->mCommonBase.getFenceTimeout();
}

EGL *SwappyGL::getEgl() {
    static thread_local EGL *egl = nullptr;
    if (!egl) {
        std::lock_guard<std::mutex> lock(mEglMutex);
        egl = mEgl.get();
    }
    return egl;
}

SwappyGL::SwappyGL(JNIEnv *env, jobject jactivity, ConstructorTag)
    : mCommonBase(env, jactivity) {
    {
        std::lock_guard<std::mutex> lock(mEglMutex);
        mEgl = EGL::create(mCommonBase.getFenceTimeout());
        if (!mEgl) {
            SWAPPY_LOGE("Failed to load EGL functions");
            mEnableSwappy = false;
            return;
        }
    }

    if (!mCommonBase.isValid()) {
        SWAPPY_LOGE("SwappyCommon could not initialize correctly.");
        mEnableSwappy = false;
        return;
    }

    mEnableSwappy =
        !gamesdk::GetSystemPropAsBool(SWAPPY_SYSTEM_PROP_KEY_DISABLE, false);
    if (!enabled()) {
        SWAPPY_LOGI("Swappy is disabled");
        return;
    }

    SWAPPY_LOGI("SwappyGL initialized successfully");
}

SwappyGL::SwappyGL(const SwappyCommonSettings &settings, TestConstructorTag)
    : mCommonBase(settings) {
    std::lock_guard<std::mutex> lock(mEglMutex);
    mEgl = EGL::create(mCommonBase.getFenceTimeout());
    mEnableSwappy = mEgl != nullptr && mCommonBase.isValid();
}

bool SwappyGL::setPresentationTime(EGLDisplay display, EGLSurface surface) {
    TRACE_CALL();

    auto displayTimings = Settings::getInstance()->getDisplayTimings();

    // if we are too close to the vsync, there is no need to set presentation
    // time
    if ((mCommonBase.getPresentationTime() - std::chrono::steady_clock::now()) <
        (mCommonBase.getRefreshPeriod() - displayTimings.sfOffset)) {
        return EGL_TRUE;
    }
    return getEgl()->setPresentationTime(display, surface,
                                         mCommonBase.getPresentationTime());
}

void SwappyGL::setBufferStuffingFixWait(int32_t n_frames) {
    TRACE_CALL();
    SwappyGL *swappy = getInstance();
    if (!swappy) {
        return;
    }
    swappy->mCommonBase.setBufferStuffingFixWait(n_frames);
}

int SwappyGL::getSupportedRefreshPeriodsNS(uint64_t *out_refreshrates,
                                           int allocated_entries) {
    TRACE_CALL();
    SwappyGL *swappy = getInstance();
    if (!swappy) {
        return -1;
    }
    return swappy->mCommonBase.getSupportedRefreshPeriodsNS(out_refreshrates,
                                                            allocated_entries);
}

void SwappyGL::resetFramePacing() {
    TRACE_CALL();
    SwappyGL *swappy = getInstance();
    if (!swappy) {
        return;
    }
    swappy->mCommonBase.resetFramePacing();
}

void SwappyGL::enableFramePacing(bool enable) {
    TRACE_INT("enableFramePacing", (int)enable);
    SwappyGL *swappy = getInstance();
    if (!swappy) {
        return;
    }
    swappy->mCommonBase.enableFramePacing(enable);
}

void SwappyGL::enableBlockingWait(bool enable) {
    TRACE_INT("enableBlockingWait", (int)enable);
    SwappyGL *swappy = getInstance();
    if (!swappy) {
        return;
    }
    swappy->mCommonBase.enableBlockingWait(enable);
}

}  // namespace swappy
