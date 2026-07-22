#pragma once

#include "FixedPresentEventContract.h"
#include "FixedTransportProfile.h"
#include "HardwareBufferRenderTargetPool.h"

#include <android/looper.h>
#include <android/native_window.h>
#include <android/surface_control.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <linux/sync_file.h>

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <optional>
#include <thread>

namespace ntk::present {

struct SurfaceControlPresentBackendTestAccess;

/**
 * Bounded callback ownership at the synchronous post-apply cut.
 *
 * Commit and complete events are consumed independently. A complete event may therefore be
 * consumed while its commit event is still queued. Successor admission still requires the exact
 * predecessor commit observation, while older records may remain retained for OnComplete; both
 * pending sets remain bounded by the K=8 callback ledger.
 */
constexpr bool postApplyCallbackRetentionExact(
        std::uint32_t retainedWaitingOnCompleteCount,
        std::uint32_t commitProofPendingNow,
        std::uint32_t completeProofPendingNow) noexcept {
    return completeProofPendingNow >= commitProofPendingNow &&
        retainedWaitingOnCompleteCount ==
            completeProofPendingNow - commitProofPendingNow &&
        commitProofPendingNow <= HardwareBufferRenderTargetPool::kSlotCount &&
        completeProofPendingNow <= HardwareBufferRenderTargetPool::kSlotCount;
}

/**
 * Retirement-and-OnCommit JOIN post-apply callback depths.
 *
 * The predecessor's exact OnCommit is consumed before a successor apply, so the synchronous
 * post-apply cut has exactly one logical unlatched submission. OnComplete records may overlap up
 * to K=8. submittedWaitLatchCount is the current frame's one outstanding OnCommit proof, not a
 * cumulative wait counter.
 */
constexpr bool postApplyLatchConjunctionDepthsExact(
        std::uint32_t callbackRecordDepth,
        std::uint32_t maxCallbackRecordDepth,
        std::uint32_t logicalUnlatchedNow,
        std::uint32_t maxLogicalUnlatched,
        std::uint32_t submittedWaitLatchCount,
        std::uint32_t commitProofPendingNow,
        std::uint32_t completeProofPendingNow,
        std::uint32_t maxCommitProofPending,
        std::uint32_t maxCompleteProofPending) noexcept {
    constexpr std::uint32_t k = HardwareBufferRenderTargetPool::kSlotCount;
    return callbackRecordDepth >= 1 && callbackRecordDepth <= k &&
        maxCallbackRecordDepth >= callbackRecordDepth &&
        maxCallbackRecordDepth <= k &&
        logicalUnlatchedNow == 1 &&
        maxLogicalUnlatched == 1 &&
        submittedWaitLatchCount == commitProofPendingNow &&
        commitProofPendingNow == 1 &&
        completeProofPendingNow >= 1 &&
        completeProofPendingNow <= callbackRecordDepth &&
        maxCommitProofPending == 1 &&
        maxCompleteProofPending >= completeProofPendingNow &&
        maxCompleteProofPending <= maxCallbackRecordDepth;
}

class SurfaceControlPresentBackend final {
public:
    using WakeCallback = void (*)(void*) noexcept;
    using SyncFileInfoFn = ::sync_file_info* (*)(std::int32_t);
    using SyncFileInfoFreeFn = void (*)(::sync_file_info*);

    enum class PreparedTransactionState : std::uint8_t {
        EMPTY = 0,
        PREPARED_NOT_CLAIMED = 1,
        CLAIMED_NOT_APPLIED = 2,
        // Wire value retained for stable telemetry. Applied ownership is
        // tracked by the bounded callback records and never stored here.
        APPLIED_DRAINING = 3,
        TERMINAL = 4,
    };

    enum class ApplyDisposition : std::uint8_t {
        NOT_APPLIED = 0,
        APPLIED = 1,
    };

    enum class ApplyReadiness : std::uint8_t {
        READY = 0,
        WAITING_PRIOR_LATCH = 1,
        FATAL = 2,
    };

    struct ExactPresentLatchObservation {
        FixedFrameIdentity identity{};
        std::uint64_t latchEventSequence = 0;
        std::int64_t latchNanos = 0;
        FixedLatchSource source = FixedLatchSource::NONE;
    };

    struct FixedPreparedFrameIdentityBase {
        std::uint64_t engineGeneration = 0;
        std::uint64_t surfaceEpoch = 0;
        std::int64_t authorityGeneration = 0;
        std::int64_t authority = 0;
        std::uint64_t workGeneration = 0;
        std::uint64_t ntkFrameId = 0;
        std::uint64_t frameSequence = 0;
        std::uint64_t capsuleSequence = 0;
    };

    struct PreparedSurfaceSubmission {
        FixedPreparedFrameIdentityBase baseIdentity{};
        ASurfaceTransaction* transaction = nullptr;
        void* cookie = nullptr;
        std::uint64_t backendSurfaceSerial = 0;
        std::uint64_t transactionSerial = 0;
        std::uint64_t bufferSlot = 0;
        std::uint64_t bufferGeneration = 0;
        std::uint64_t acquireFenceSerial = 0;
        std::int64_t prepareBeginNanos = 0;
        std::int64_t prepareEndNanos = 0;
        std::int64_t transportBoundNanos = 0;
        std::uint64_t transportProfileDigest = 0;
        std::uint64_t timingGeneration = 0;
        std::uint32_t setBufferCount = 0;
        std::uint32_t acquireFenceDupCount = 0;
        std::uint32_t setBufferPending = 0;
        std::uint32_t setFrameTimelineCount = 0;
        std::uint32_t applyCount = 0;
        std::uint32_t callbackCookieIndex = UINT32_MAX;
        AppliedBufferRef previousAppliedBufferRef{};
        std::uint64_t reservedAppliedBufferRefSerial = 0;
        bool backpressureEnablePending = false;
        bool firstStage = false;
        PreparedTransactionState state = PreparedTransactionState::EMPTY;
    };

    struct SubmissionReceipt {
        FixedFrameIdentity identity{};
        std::int64_t transactionApplyBeginNanos = 0;
        std::int64_t frameTimelineSetEndNanos = 0;
        std::int64_t bufferSetEndNanos = 0;
        std::int64_t transactionApplyEndNanos = 0;
        std::uint32_t setBufferCount = 0;
        std::uint32_t setFrameTimelineCount = 0;
        std::uint32_t transactionApplyCount = 0;
        ApplyDisposition applyDisposition = ApplyDisposition::NOT_APPLIED;
        AppliedBufferRef previousAppliedBufferRef{};
        AppliedBufferRef appliedBufferRef{};
        bool applyBeforeAcquireSignalProven = false;
        bool submitted = false;
    };

    struct ConservationSnapshot {
        std::uint32_t outstandingSubmissionCount = 0;
        std::uint32_t maxOutstandingSubmissionCount = 0;
        PreparedTransactionState preparedTransactionState =
            PreparedTransactionState::EMPTY;
        std::array<HardwareBufferRenderTargetPool::SlotState,
                   HardwareBufferRenderTargetPool::kSlotCount> poolStates{};
        std::uint32_t pendingFenceWatchCount = 0;
        std::uint32_t activeFenceWatchCount = 0;
        std::uint64_t teardownReleaseEventSequence = 0;
        std::uint32_t callbackRecordDepth = 0;
        std::uint32_t maxCallbackRecordDepth = 0;
        std::uint32_t previousReleaseRecordDepth = 0;
        std::uint32_t acquireFenceRecordDepth = 0;
        std::uint32_t appOwnedAcquireFdCount = 0;
        std::uint32_t logicalUnlatchedNow = 0;
        std::uint32_t maxLogicalUnlatched = 0;
        std::uint64_t latestAppliedBufferRefSerial = 0;
        std::uint64_t latestConsumedLatchRefSerial = 0;
        // Current submitted frame awaiting its exact compositor OnCommit proof.
        std::uint32_t submittedWaitLatchCount = 0;
        std::uint32_t latchedCurrentCount = 0;
        std::uint32_t releaseWaitCount = 0;
        // Successor applies while the predecessor's exact OnComplete proof is
        // still unconsumed, regardless of callback-thread arrival time.
        std::uint64_t applyBeforePriorCompleteCount = 0;
        std::uint64_t applyBeforePriorCommitConsumedCount = 0;
        std::uint64_t retainedWaitingOnCompleteCount = 0;
        std::uint32_t priorOnCompletePendingAtSuccessorApply = 0;
        std::uint64_t applyBeforeAcquireSignalProvenCount = 0;
        std::uint64_t backendInvariantFatalCount = 0;
        std::int64_t lastLatchConsumedToSuccessorApplyNanos = 0;
        std::int64_t lastSuccessorApplyMinusPriorCompleteNanos = 0;
        std::int64_t lastSuccessorReadyMinusPriorCompleteNanos = 0;
        std::uint32_t commitProofPendingNow = 0;
        std::uint32_t completeProofPendingNow = 0;
        std::uint32_t maxCommitProofPending = 0;
        std::uint32_t maxCompleteProofPending = 0;
        std::uint32_t heldFrameworkRefCount = 0;
        std::uint32_t maxHeldFrameworkRefCount = 0;
        std::uint32_t freeReusableCount = 0;
        std::uint32_t minFreeReusableCount =
            HardwareBufferRenderTargetPool::kSlotCount;
        std::uint32_t appOwnedBufferDomainNow = 0;
        std::uint32_t minAppOwnedBufferDomain =
            HardwareBufferRenderTargetPool::kSlotCount;
        std::uint64_t backpressureEnableCount = 0;
        std::uint64_t backpressureDisableCount = 0;
        std::uint64_t capacityExhaustedCount = 0;
        std::uint64_t capacityWaitCount = 0;
    };

    SurfaceControlPresentBackend() = default;
    ~SurfaceControlPresentBackend();

    SurfaceControlPresentBackend(const SurfaceControlPresentBackend&) = delete;
    SurfaceControlPresentBackend& operator=(const SurfaceControlPresentBackend&) = delete;

    /**
     * Allocates the EGL/AHardwareBuffer presentation infrastructure without creating or
     * attaching an ASurfaceControl. This is safe to overlap with the opened reader's network
     * and decode work because it has no parent window, buffer submission, or draw path.
     */
    bool prepare(
        EGLDisplay display,
        std::uint32_t width,
        std::uint32_t height);

    bool attach(
        EGLDisplay display,
        ANativeWindow* parentWindow,
        std::uint32_t width,
        std::uint32_t height,
        std::uint64_t surfaceEpoch,
        WakeCallback wakeCallback,
        void* wakeContext);

    HardwareBufferRenderTargetPool::RenderTarget* acquireRenderTarget();
    bool bindRenderTarget(HardwareBufferRenderTargetPool::RenderTarget& target);
    bool exportAcquireFence(
        HardwareBufferRenderTargetPool::RenderTarget& target,
        std::int64_t renderBeginNanos,
        std::int64_t renderEndNanos,
        GpuSubmissionProof* proof);

    bool prepareBufferTransaction(
        const FixedPreparedFrameIdentityBase& baseIdentity,
        HardwareBufferRenderTargetPool::RenderTarget& target,
        bool firstStage,
        const FixedTransportProfile& profile,
        PreparedSurfaceSubmission* prepared,
        SwappyFixedExternalTransportReady* proof);

    ApplyReadiness queryApplyReadiness(
        const PreparedSurfaceSubmission& prepared) noexcept;
    /**
     * Rolling-reader admission. The UI producer remains bound to the exact predecessor OnCommit;
     * fixed-scene callers continue to use [queryApplyReadiness].
     */
    ApplyReadiness queryDirectApplyReadiness(
        const PreparedSurfaceSubmission& prepared) noexcept;

    ApplyDisposition applyPreparedBufferTransaction(
        PreparedSurfaceSubmission& prepared,
        const SwappyFixedExternalClaim& claim,
        SubmissionReceipt* receipt);

    /**
     * Applies a prepared buffer from the demand-bound rolling renderer.
     *
     * This lane deliberately skips Swappy's sealed-scene reservation protocol, but it does not
     * skip any physical ownership rule: the predecessor OnCommit JOIN, acquire-fence transfer,
     * bounded callback ledger, previous-buffer release fence, and exact SurfaceControl latch
     * evidence are shared with the fixed renderer.
     */
    ApplyDisposition applyPreparedBufferTransactionDirect(
        PreparedSurfaceSubmission& prepared,
        SubmissionReceipt* receipt);

    bool abortPreparedBufferTransaction(
        PreparedSurfaceSubmission& prepared);
    bool abortRenderTargetBeforePreparation(
        std::uint64_t bufferSlot, std::uint64_t bufferGeneration);

    bool drainEvent(FixedPresentEvent* event);
    bool hasPendingEvent();
    bool consumeCompositorLatch(
        const FixedPresentEvent& event,
        ExactPresentLatchObservation* observation) noexcept;
    bool consumeTransactionCompleted(
        const FixedPresentEvent& event) noexcept;
    bool consumePreviousBufferReleased(
        const FixedPresentEvent& event) noexcept;
    bool consumeAcquireFenceSignaled(
        const FixedPresentEvent& event) noexcept;
    bool eventOverflowed() const noexcept {
        return eventOverflowed_.load(std::memory_order_acquire);
    }
    // The prepared lane is independent from the one-deep applied lane. A
    // successor may be prebuilt while its predecessor awaits OnComplete.
    bool hasPreparationCapacity() const noexcept {
        return attached_ && childSurface_ != nullptr &&
            preparedTransactionState_ == PreparedTransactionState::EMPTY &&
            preparedTransactionSerial_ == 0;
    }
    bool hasDirectSubmissionCapacity() const noexcept {
        return hasPreparationCapacity() &&
            logicalUnlatchedNow_ < kMaxDirectLogicalUnlatched;
    }
    bool hasOutstandingSubmission() const noexcept {
        return callbackRecordCount() != 0;
    }
    bool detachAfterEvidenceDrained();
    // Lifecycle fallback for a parent ANativeWindow that is already being replaced. This is
    // legal only after every applied callback/fence/event has been consumed; it releases the
    // app's chain-head ownership before destroy() drops the child SurfaceControl.
    bool retireAfterParentLifecycleEvidenceDrained();
    bool destroy();
    ConservationSnapshot conservationSnapshot();

    HardwareBufferRenderTargetPool& pool() noexcept { return pool_; }
    std::uint64_t surfaceSerial() const noexcept { return surfaceSerial_; }
    bool prepared() const noexcept { return prepared_; }
    bool preparedFor(
            EGLDisplay display,
            std::uint32_t width,
            std::uint32_t height) const noexcept {
        return prepared_ && display_ == display && width_ == width && height_ == height;
    }

private:
    friend struct SurfaceControlPresentBackendTestAccess;
    static constexpr std::size_t kEventCapacity = 64;
    static constexpr std::size_t kMaxAppliedCallbackRecords = 8;
    static constexpr std::size_t kMaxPreviousReleaseRecords = 7;
    static constexpr std::size_t kMaxAcquireFenceRecords = 8;
    // A one-deep chain necessarily misses every other host-vsync: the successor can only be
    // applied after its predecessor's OnCommit callback returns. Two identity-tracked buffers let
    // rendering and callback delivery overlap while remaining far below the eight-slot ledger.
    static constexpr std::uint32_t kMaxDirectLogicalUnlatched = 2;
    static constexpr std::size_t kMaxCallbackCookies =
        kMaxAppliedCallbackRecords + 1;
    static constexpr std::size_t kMaxFenceWatches =
        kMaxAcquireFenceRecords + kMaxPreviousReleaseRecords;

    struct BufferIdentity {
        std::uint64_t slot = 0;
        std::uint64_t generation = 0;
    };

    struct SubmissionCookie {
        SurfaceControlPresentBackend* backend = nullptr;
        FixedFrameIdentity identity{};
        AppliedBufferRef previousAppliedBufferRef{};
        bool hasPreviousAppliedBufferRef = false;
        bool teardown = false;
        std::uint32_t slotIndex = UINT32_MAX;
        std::atomic<bool> inUse{false};
        std::atomic<std::uint32_t> onCommitCount{0};
        std::atomic<std::uint32_t> onCompleteCount{0};
        std::atomic<std::uint64_t> onCommitEventSequence{0};
        std::atomic<std::uint64_t> onCompleteEventSequence{0};
        std::atomic<std::int64_t> onCommitLatchNanos{0};
        std::atomic<std::int64_t> onCommitObservedNanos{0};
        std::atomic<std::int64_t> onCompleteObservedNanos{0};
        std::atomic<std::uint32_t> lifecycleFlags{0};
    };

    struct AppliedCallbackRecord {
        FixedFrameIdentity identity{};
        AppliedBufferRef producedRef{};
        std::optional<AppliedBufferRef> replacedRef{};
        std::uint64_t latchEventSequence = 0;
        std::int64_t latchNanos = 0;
        std::int64_t commitCallbackObservedNanos = 0;
        std::uint64_t completeEventSequence = 0;
        std::int64_t completeCallbackObservedNanos = 0;
        std::uint32_t consumedOnCommitCount = 0;
        std::uint32_t consumedOnCompleteCount = 0;
        std::uint32_t cookieIndex = UINT32_MAX;
        std::int64_t successorReadyNanos = 0;
        std::int64_t successorApplyBeginNanos = 0;
        bool applyIssued = false;
        bool commitEventConsumed = false;
        bool completeEventConsumed = false;
        bool poisoned = false;
    };

    struct PreviousReleaseRecord {
        FixedFrameIdentity replacingTransactionIdentity{};
        AppliedBufferRef replacedRef{};
        std::uint64_t releaseEventSequence = 0;
        bool releaseFencePending = false;
        bool released = false;
        bool poisoned = false;
    };

    enum class LocalAcquirePhase : std::uint8_t {
        EXPORTED_UNBOUND = 0,
        BOUND_TO_PREPARED = 1,
        CLAIMED_NOT_TRANSFERRED = 2,
    };

    struct LocalAcquireFenceOwner {
        BufferIdentity buffer{};
        std::uint64_t acquireFenceSerial = 0;
        int frameworkAcquireFd = -1;
        int proofAcquireFd = -1;
        std::uint64_t preparedTransactionSerial = 0;
        LocalAcquirePhase phase = LocalAcquirePhase::EXPORTED_UNBOUND;
    };

    enum class AcquireProofPhase : std::uint8_t {
        RESERVED = 0,
        PENDING_REGISTER = 1,
        ACTIVE_WAIT_SIGNAL = 2,
        POISONED_DRAINING = 3,
    };

    struct AcquireFenceRecord {
        FixedFrameIdentity identity{};
        BufferIdentity buffer{};
        std::uint64_t acquireFenceSerial = 0;
        int proofFd = -1;
        AcquireProofPhase phase = AcquireProofPhase::RESERVED;
        std::int64_t signalNanos = 0;
        std::int64_t observedNanos = 0;
        std::uint32_t closeCount = 0;
        bool poisoned = false;
    };

    enum class FenceWatchKind : std::uint8_t {
        ACQUIRE_PROOF = 1,
        PREVIOUS_RELEASE = 2,
    };

    struct PendingFenceWatch {
        int fd = -1;
        FixedPresentEvent event{};
        FenceWatchKind kind = FenceWatchKind::PREVIOUS_RELEASE;
        bool occupied = false;
    };

    struct ActiveFenceWatch {
        SurfaceControlPresentBackend* backend = nullptr;
        int fd = -1;
        FixedPresentEvent event{};
        FenceWatchKind kind = FenceWatchKind::PREVIOUS_RELEASE;
        bool occupied = false;
    };

    struct SurfaceApi {
        using TransactionCallback = void (*)(void*, ASurfaceTransactionStats*);
        ASurfaceControl* (*createFromWindow)(ANativeWindow*, const char*) = nullptr;
        void (*releaseSurface)(ASurfaceControl*) = nullptr;
        ASurfaceTransaction* (*createTransaction)() = nullptr;
        void (*deleteTransaction)(ASurfaceTransaction*) = nullptr;
        void (*applyTransaction)(ASurfaceTransaction*) = nullptr;
        void (*setOnComplete)(
            ASurfaceTransaction*, void*, TransactionCallback) = nullptr;
        void (*setOnCommit)(
            ASurfaceTransaction*, void*, TransactionCallback) = nullptr;
        void (*reparent)(
            ASurfaceTransaction*, ASurfaceControl*, ASurfaceControl*) = nullptr;
        void (*setVisibility)(
            ASurfaceTransaction*, ASurfaceControl*,
            ASurfaceTransactionVisibility) = nullptr;
        void (*setBuffer)(
            ASurfaceTransaction*, ASurfaceControl*, AHardwareBuffer*, int) = nullptr;
        void (*setGeometry)(
            ASurfaceTransaction*, ASurfaceControl*,
            const ARect&, const ARect&, std::int32_t) = nullptr;
        void (*setBufferTransparency)(
            ASurfaceTransaction*, ASurfaceControl*,
            ASurfaceTransactionTransparency) = nullptr;
        void (*setBufferAlpha)(
            ASurfaceTransaction*, ASurfaceControl*, float) = nullptr;
        void (*setEnableBackPressure)(
            ASurfaceTransaction*, ASurfaceControl*, bool) = nullptr;
        void (*setFrameTimeline)(ASurfaceTransaction*, AVsyncId) = nullptr;
        std::int64_t (*getLatchTime)(ASurfaceTransactionStats*) = nullptr;
        int (*getPreviousReleaseFenceFd)(
            ASurfaceTransactionStats*, ASurfaceControl*) = nullptr;

        bool complete() const noexcept;
    };

    static void onCommitted(
        void* context, ASurfaceTransactionStats* stats) noexcept;
    static void onCompleted(
        void* context, ASurfaceTransactionStats* stats) noexcept;
    static int onFenceControlFd(
        int fd, int events, void* data) noexcept;
    static int onReleaseFenceFd(
        int fd, int events, void* data) noexcept;
    static bool fenceReactorInitializationSucceeded(
        ALooper* looper, int controlRegistrationResult) noexcept;

    void publishEvent(const FixedPresentEvent& event) noexcept;
    bool enqueueFence(
        int fd, FixedPresentEvent event, FenceWatchKind kind) noexcept;
    void enqueueReleaseFence(int fd, FixedPresentEvent event) noexcept;
    bool publishAcquireFenceProofAfterApply(
        std::size_t recordIndex, int proofFd,
        FixedPresentEvent event) noexcept;
    void releaseFenceLoop();
    void registerPendingFenceWatches();
    void finishFenceWatch(int fd, FixedPresentEvent event) noexcept;
    void stopFenceReactor();
    static std::int64_t monotonicNowNanos() noexcept;
    std::uint32_t callbackRecordCount() const noexcept;
    std::uint32_t previousReleaseRecordCount() const noexcept;
    std::uint32_t acquireFenceRecordCount() const noexcept;
    std::uint32_t appOwnedAcquireFdCount() const noexcept;
    AppliedCallbackRecord* findAppliedCallbackRecord(
        const FixedFrameIdentity& identity) noexcept;
    const AppliedCallbackRecord* findAppliedCallbackRecord(
        const FixedFrameIdentity& identity) const noexcept;
    PreviousReleaseRecord* findPreviousReleaseRecord(
        const FixedFrameIdentity& identity) noexcept;
    const PreviousReleaseRecord* findPreviousReleaseRecord(
        const FixedFrameIdentity& identity) const noexcept;
    std::optional<std::size_t> freeAppliedCallbackRecordIndex() const noexcept;
    std::optional<std::size_t> freePreviousReleaseRecordIndex() const noexcept;
    std::optional<std::size_t> freeAcquireFenceRecordIndex() const noexcept;
    std::optional<std::size_t> acquireCallbackCookie() noexcept;
    void releaseCallbackCookie(std::size_t index) noexcept;
    void completeCallbackPublication(SubmissionCookie& cookie) noexcept;
    void completeCallbackRecordConsumption(std::size_t index) noexcept;
    bool stateInvariantsHold() const noexcept;
    ApplyReadiness queryApplyReadinessImpl(
        const PreparedSurfaceSubmission& prepared,
        bool allowDirectPipeline) noexcept;
    bool closeAndClearLocalAcquireFence() noexcept;
    ApplyDisposition applyPreparedBufferTransactionImpl(
        PreparedSurfaceSubmission& prepared,
        const SwappyFixedExternalClaim& claim,
        SubmissionReceipt* receipt,
        bool directSubmission);
    EGLDisplay display_ = EGL_NO_DISPLAY;
    void* androidLibrary_ = nullptr;
    void* syncLibrary_ = nullptr;
    SurfaceApi surfaceApi_{};
    PFNEGLCREATESYNCKHRPROC createSync_ = nullptr;
    PFNEGLDESTROYSYNCKHRPROC destroySync_ = nullptr;
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC dupNativeFenceFd_ = nullptr;
    SyncFileInfoFn syncFileInfo_ = nullptr;
    SyncFileInfoFreeFn syncFileInfoFree_ = nullptr;
    ANativeWindow* parentWindow_ = nullptr;
    ASurfaceControl* childSurface_ = nullptr;
    HardwareBufferRenderTargetPool pool_{};
    std::uint32_t width_ = 0;
    std::uint32_t height_ = 0;
    std::uint32_t destinationWidth_ = 0;
    std::uint32_t destinationHeight_ = 0;
    std::uint64_t surfaceEpoch_ = 0;
    std::uint64_t surfaceSerial_ = 0;
    std::uint64_t transactionSerial_ = 0;
    std::uint64_t acquireFenceSerial_ = 0;
    std::uint64_t appliedBufferRefSerial_ = 0;
    std::uint64_t directAdmissionSequence_ = 0;
    std::uint64_t directFrameTimelineIdentity_ = 0;
    std::atomic<std::uint64_t> eventSequence_{0};
    std::optional<AppliedBufferRef> latestAppliedBufferRef_;
    std::optional<AppliedBufferRef> latestConsumedCompositorLatchRef_;
    std::uint64_t latestConsumedCompositorLatchEventSequence_ = 0;
    std::int64_t latestConsumedCompositorLatchNanos_ = 0;
    std::int64_t latestConsumedCompositorLatchObservedNanos_ = 0;
    std::uint32_t logicalUnlatchedNow_ = 0;
    std::uint32_t maxLogicalUnlatched_ = 0;
    WakeCallback wakeCallback_ = nullptr;
    void* wakeContext_ = nullptr;
    bool prepared_ = false;
    bool attached_ = false;
    PreparedTransactionState preparedTransactionState_ =
        PreparedTransactionState::EMPTY;
    std::uint64_t preparedTransactionSerial_ = 0;
    std::array<std::optional<AppliedCallbackRecord>,
               kMaxAppliedCallbackRecords> appliedCallbacks_{};
    std::array<std::optional<PreviousReleaseRecord>,
               kMaxPreviousReleaseRecords> previousReleases_{};
    std::optional<LocalAcquireFenceOwner> localAcquireFence_;
    std::array<std::optional<AcquireFenceRecord>,
               kMaxAcquireFenceRecords> acquireFences_{};
    std::array<SubmissionCookie, kMaxCallbackCookies> callbackCookies_{};
    std::uint32_t maxAppliedCallbackRecordCount_ = 0;
    std::uint32_t maxCommitProofPending_ = 0;
    std::uint32_t maxCompleteProofPending_ = 0;
    std::uint64_t applyBeforePriorCompleteCount_ = 0;
    std::uint64_t applyBeforePriorCommitConsumedCount_ = 0;
    std::uint32_t priorOnCompletePendingAtSuccessorApply_ = 0;
    std::uint64_t backendInvariantFatalCount_ = 0;
    std::uint64_t applyBeforeAcquireSignalProvenCount_ = 0;
    std::int64_t lastLatchConsumedToSuccessorApplyNanos_ = 0;
    std::int64_t lastSuccessorApplyMinusPriorCompleteNanos_ = 0;
    std::int64_t lastSuccessorReadyMinusPriorCompleteNanos_ = 0;
    bool backpressureEnabled_ = false;
    std::uint64_t backpressureEnableCount_ = 0;
    std::uint64_t backpressureDisableCount_ = 0;
    std::uint64_t capacityExhaustedCount_ = 0;
    std::uint64_t capacityWaitCount_ = 0;
    std::uint32_t maxHeldFrameworkRefCount_ = 0;
    std::uint32_t minFreeReusableCount_ =
        HardwareBufferRenderTargetPool::kSlotCount;
    std::uint32_t minAppOwnedBufferDomain_ =
        HardwareBufferRenderTargetPool::kSlotCount;

    std::mutex eventMutex_;
    std::condition_variable eventCondition_;
    std::array<FixedPresentEvent, kEventCapacity> events_{};
    std::size_t eventRead_ = 0;
    std::size_t eventWrite_ = 0;
    std::size_t eventCount_ = 0;
    std::atomic<bool> eventOverflowed_{false};
    std::thread fenceThread_;
    int fenceControlFd_ = -1;
    ALooper* fenceLooper_ = nullptr;
    mutable std::mutex fenceMutex_;
    std::condition_variable fenceReady_;
    std::array<PendingFenceWatch, kMaxFenceWatches>
        pendingFenceWatches_{};
    std::array<ActiveFenceWatch, kMaxFenceWatches>
        activeFenceWatches_{};
    bool fenceLooperReady_ = false;
    bool fenceLooperFailed_ = false;
    std::atomic<bool> fenceStopping_{false};

    std::mutex teardownMutex_;
    std::condition_variable teardownCondition_;
    bool teardownCompleted_ = false;
    std::atomic<std::uint64_t> teardownReleaseEventSequence_{0};
};

}  // namespace ntk::present
