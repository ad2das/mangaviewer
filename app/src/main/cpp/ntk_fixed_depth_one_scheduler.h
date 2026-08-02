#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <optional>

namespace ntk::scheduler {

enum class HeadFrameState : std::uint8_t {
    EMPTY = 0,
    CONTENT_FROZEN,
    SWAPPY_RESERVED_PREPARING,
    GPU_TARGET_OWNED,
    DRAW_ISSUED,
    FRAME_ID_RESERVED,
    BACKEND_READY_UNRESERVED,
    SWAPPY_RESERVED,
    EXTERNAL_CLAIMED_NOT_APPLIED,
    PHASE_COMMITTING,
    FAILED,
};

enum class FrameKind : std::uint8_t {
    STAGE = 0,
    MOVE = 1,
    TERMINAL = 2,
};

enum class ReducerGestureState : std::uint8_t {
    IDLE = 0,
    ACTIVE,
};

enum class TerminalObligationState : std::uint8_t {
    ACCEPTED = 0,
    QUEUED_IN_SUCCESSOR,
    PREPARED,
    SUBMITTED,
    JOINED,
    LOST,
};

struct ViewState {
    std::int64_t scroll_top = 0;
    float velocity_px_per_second = 0.0F;
    int scroll_direction = 0;
};

struct InputEnvelope {
    std::uint64_t input_watermark = 0;
    std::int64_t event_oldest_ns = 0;
    std::int64_t event_newest_ns = 0;
    std::int64_t main_ingress_oldest_ns = 0;
    std::int64_t main_ingress_newest_ns = 0;
    std::int64_t receipt_oldest_ns = 0;
    std::int64_t receipt_newest_ns = 0;
    std::int64_t mutation_oldest_ns = 0;
    std::int64_t mutation_newest_ns = 0;

    bool hasInput() const noexcept { return input_watermark != 0; }
    bool ordered() const noexcept;
    void clear() noexcept { *this = {}; }
    void recordInput(
        std::uint64_t sequence,
        std::int64_t eventNanos,
        std::int64_t mainIngressNanos,
        std::int64_t receiptNanos) noexcept;
    void recordMutation(std::int64_t mutationNanos) noexcept;
    void merge(const InputEnvelope& other) noexcept;
};

struct TerminalMove {
    bool valid = false;
    std::int64_t event_time_ns = 0;
    std::int64_t main_ingress_ns = 0;
    std::int64_t receipt_time_ns = 0;
    std::uint64_t input_sequence = 0;
    std::uint64_t gesture_generation = 0;
    float x = 0.0F;
    float y = 0.0F;
    int pointer_id = -1;
};

struct InputSample {
    int action = 0;
    std::int64_t event_time_ns = 0;
    std::int64_t main_ingress_ns = 0;
    std::int64_t receipt_time_ns = 0;
    std::uint64_t input_sequence = 0;
    std::uint64_t gesture_generation = 0;
    TerminalMove terminal_move{};
    float x = 0.0F;
    float y = 0.0F;
    int pointer_id = -1;
};

struct FrameScope {
    std::uint64_t surface_epoch = 0;
    std::int64_t authority_generation = 0;
    std::int64_t authority = 0;
    std::int64_t scene_version = 0;
};

struct InputReducerState {
    ViewState view{};
    ReducerGestureState gesture_state = ReducerGestureState::IDLE;
    std::uint64_t gesture_generation = 0;
    std::uint64_t visual_demand_epoch = 0;
    int active_pointer_id = -1;
    float last_touch_y = 0.0F;
    double fractional_scroll_remainder = 0.0;
    std::int64_t last_event_time_ns = 0;
    std::uint64_t applied_move_sequence = 0;
    InputEnvelope unassigned_input{};
};

struct SuccessorFrameWork {
    std::uint64_t work_generation = 0;
    FrameScope scope{};
    FrameKind kind = FrameKind::MOVE;
    bool terminal = false;
    std::uint64_t gesture_generation = 0;
    std::uint64_t terminal_input_sequence = 0;
    ViewState view_state{};
    InputEnvelope input{};
    std::uint64_t visual_demand_epoch = 0;
    std::uint64_t visual_mutation_serial = 0;
    bool visible_state_changed = false;
    ViewState predecessor_scheduled_view{};
};

struct ReductionResult {
    bool valid = false;
    bool frame_cause = false;
    bool terminal = false;
    std::uint64_t terminal_input_sequence = 0;
};

struct TerminalObligation {
    std::uint64_t gesture_generation = 0;
    std::uint64_t input_sequence = 0;
    std::uint64_t work_generation = 0;
    TerminalObligationState state = TerminalObligationState::ACCEPTED;
};

struct SchedulerCounters {
    std::uint64_t max_logical_producer_depth = 0;
    std::uint64_t max_successor_depth = 0;
    std::uint64_t max_swappy_reservation_depth = 0;
    std::uint64_t max_backend_prepared_depth = 0;
    std::uint64_t spurious_commit_attempt_count = 0;
    std::uint64_t matching_join_open_publish_count = 0;
    std::uint64_t duplicate_join_open_count = 0;
    std::uint64_t foreign_notice_count = 0;
    std::uint64_t candidate_notice_ignored_count = 0;
    std::uint64_t readiness_deferred_opportunity_count = 0;
    std::uint64_t opportunity_consumed_closed_count = 0;
    std::uint64_t opportunity_consumed_submitted_count = 0;
    std::uint64_t opportunity_protocol_fatal_count = 0;
    std::uint64_t terminal_accepted_count = 0;
    std::uint64_t terminal_queued_count = 0;
    std::uint64_t terminal_prepared_count = 0;
    std::uint64_t terminal_submitted_count = 0;
    std::uint64_t terminal_joined_count = 0;
    std::uint64_t terminal_lost_count = 0;
};

struct AbortOwnershipActions {
    bool abort_render_target = false;
    bool abort_backend_transaction = false;
    bool abort_swappy_reservation = false;
    bool abort_external_claim = false;
    bool drain_submitted = false;
};

constexpr AbortOwnershipActions abortOwnershipActions(
        HeadFrameState state) noexcept {
    switch (state) {
        case HeadFrameState::GPU_TARGET_OWNED:
        case HeadFrameState::DRAW_ISSUED:
        case HeadFrameState::FRAME_ID_RESERVED:
            return {
                .abort_render_target = true,
                .abort_swappy_reservation = true,
            };
        case HeadFrameState::SWAPPY_RESERVED_PREPARING:
            return {.abort_swappy_reservation = true};
        case HeadFrameState::BACKEND_READY_UNRESERVED:
            return {
                .abort_backend_transaction = true,
                .abort_swappy_reservation = true,
            };
        case HeadFrameState::SWAPPY_RESERVED:
            return {
                .abort_backend_transaction = true,
                .abort_swappy_reservation = true,
            };
        case HeadFrameState::EXTERNAL_CLAIMED_NOT_APPLIED:
            return {
                .abort_backend_transaction = true,
                .abort_external_claim = true,
            };
        case HeadFrameState::PHASE_COMMITTING:
            return {.drain_submitted = true};
        default:
            return {};
    }
}

constexpr bool reservationFollowsBackendReady(
        std::int64_t prepareEndNanos,
        std::int64_t reservationNanos,
        std::uint64_t reservationDepth) noexcept {
    return prepareEndNanos > 0 &&
        reservationNanos >= prepareEndNanos && reservationDepth == 1;
}

constexpr bool reservationPrecedesGpuWork(
        std::int64_t reservationNanos,
        std::int64_t drawBeginNanos,
        std::uint64_t reservationDepth) noexcept {
    return reservationNanos > 0 &&
        drawBeginNanos >= reservationNanos && reservationDepth == 1;
}

struct FixedOpportunityIdentity {
    std::uint64_t work_generation = 0;
    std::uint64_t reservation_sequence = 0;
    std::uint64_t opportunity_sequence = 0;
    std::uint64_t candidate_sequence = 0;
    std::uint64_t notice_sequence = 0;

    bool valid() const noexcept {
        return work_generation != 0 && reservation_sequence != 0 &&
            opportunity_sequence != 0 && candidate_sequence != 0 &&
            notice_sequence != 0;
    }

    friend bool operator==(
            const FixedOpportunityIdentity& lhs,
            const FixedOpportunityIdentity& rhs) noexcept {
        return lhs.work_generation == rhs.work_generation &&
            lhs.reservation_sequence == rhs.reservation_sequence &&
            lhs.opportunity_sequence == rhs.opportunity_sequence &&
            lhs.candidate_sequence == rhs.candidate_sequence &&
            lhs.notice_sequence == rhs.notice_sequence;
    }
};

class FixedOpportunityGate final {
public:
    enum class PublishResult : std::uint8_t {
        PUBLISHED,
        DUPLICATE,
        FOREIGN,
        STALE,
        INVALID,
        OVERFLOW,
    };

    void reset() noexcept;
    bool arm(
        std::uint64_t workGeneration,
        std::uint64_t reservationSequence) noexcept;
    PublishResult publish(const FixedOpportunityIdentity& identity) noexcept;
    std::optional<FixedOpportunityIdentity> beginReadyAttempt() noexcept;
    bool finishConsumed(const FixedOpportunityIdentity& identity) noexcept;
    void cancelArmedReservation() noexcept;
    bool hasPending() const noexcept;
    bool attemptInFlight() const noexcept;
    std::uint64_t lastConsumedOpportunity() const noexcept;

private:
    mutable std::mutex mutex_;
    std::uint64_t armed_work_generation_ = 0;
    std::uint64_t armed_reservation_sequence_ = 0;
    std::uint64_t last_consumed_opportunity_ = 0;
    std::optional<FixedOpportunityIdentity> pending_{};
    std::optional<FixedOpportunityIdentity> in_flight_{};
};

class FixedDepthOneScheduler final {
public:
    static constexpr std::size_t kTerminalCapacity = 128;
    static constexpr std::size_t kControlCapacity = 128;
    static constexpr bool canAcceptControl(std::size_t depth) noexcept {
        return depth < kControlCapacity;
    }

    void reset(const ViewState& initialView) noexcept;

    const InputReducerState& reducer() const noexcept { return reducer_; }
    const std::optional<SuccessorFrameWork>& successor() const noexcept {
        return successor_;
    }
    bool successorTerminal() const noexcept {
        return successor_.has_value() && successor_->terminal;
    }
    bool headPresent() const noexcept { return head_present_; }
    std::uint64_t producerDepth() const noexcept {
        return (head_present_ ? 1U : 0U) +
            (successor_.has_value() ? 1U : 0U);
    }
    bool canDrainNextControl() const noexcept { return !successorTerminal(); }
    bool hasRunnableControlQueue(std::size_t depth) const noexcept {
        return depth != 0 && canDrainNextControl();
    }
    std::uint64_t visualMutationSerial() const noexcept {
        return visual_mutation_serial_;
    }

    ReductionResult reduceControl(
        const InputSample& input,
        std::int64_t maximumScroll,
        std::int64_t mutationNanos) noexcept;
    ReductionResult reduceMove(
        const InputSample& input,
        std::int64_t maximumScroll,
        std::int64_t mutationNanos) noexcept;
    bool foldReduction(
        const ReductionResult& result,
        const FrameScope& scope,
        std::uint64_t workGeneration,
        const ViewState& predecessorScheduledView) noexcept;
    bool queueStage(
        const FrameScope& scope,
        std::uint64_t workGeneration,
        const ViewState& view) noexcept;
    std::optional<SuccessorFrameWork> promoteSuccessor() noexcept;
    void discardProducerWork() noexcept;

    void noteHeadPresent(bool present) noexcept;
    void noteSwappyReservationDepth(std::uint64_t depth) noexcept;
    void noteBackendPreparedDepth(std::uint64_t depth) noexcept;
    void noteSpuriousCommitAttempt() noexcept;
    void noteMatchingJoinOpenPublish() noexcept;
    void noteDuplicateJoinOpen() noexcept;
    void noteForeignNotice() noexcept;
    void noteCandidateNoticeIgnored() noexcept;
    void noteReadinessDeferredOpportunity() noexcept;
    void noteOpportunityConsumedClosed() noexcept;
    void noteOpportunityConsumedSubmitted() noexcept;
    void noteOpportunityProtocolFatal() noexcept;

    bool appendAcceptedTerminal(
        std::uint64_t gestureGeneration,
        std::uint64_t inputSequence) noexcept;
    bool markTerminalQueued(
        std::uint64_t gestureGeneration,
        std::uint64_t inputSequence,
        std::uint64_t workGeneration) noexcept;
    bool markTerminalPrepared(
        std::uint64_t gestureGeneration,
        std::uint64_t inputSequence,
        std::uint64_t workGeneration) noexcept;
    bool markTerminalSubmitted(
        std::uint64_t gestureGeneration,
        std::uint64_t inputSequence,
        std::uint64_t workGeneration) noexcept;
    bool markTerminalJoined(
        std::uint64_t gestureGeneration,
        std::uint64_t inputSequence,
        std::uint64_t workGeneration) noexcept;
    std::uint64_t markOutstandingTerminalsLost() noexcept;
    std::uint64_t markUnsubmittedTerminalsLost() noexcept;
    bool hasUnjoinedTerminalObligation() const noexcept;
    bool hasRunnableTerminalObligation() const noexcept;
    bool terminalConservationExact() const noexcept;
    bool normalTerminalConservationExact() const noexcept;
    SchedulerCounters counters() const noexcept;

private:
    bool applyScroll(
        const InputSample& input,
        std::int64_t maximumScroll,
        std::int64_t mutationNanos) noexcept;
    void recordInput(const InputSample& input) noexcept;
    bool transitionTerminal(
        std::uint64_t gestureGeneration,
        std::uint64_t inputSequence,
        std::uint64_t workGeneration,
        TerminalObligationState expected,
        TerminalObligationState next) noexcept;
    void updateDepthCounters() noexcept;

    InputReducerState reducer_{};
    std::optional<SuccessorFrameWork> successor_{};
    std::uint64_t visual_demand_epoch_counter_ = 0;
    std::uint64_t visual_mutation_serial_ = 0;
    bool head_present_ = false;

    mutable std::mutex terminal_mutex_;
    std::array<std::optional<TerminalObligation>, kTerminalCapacity> terminals_{};
    std::size_t terminal_write_index_ = 0;
    SchedulerCounters counters_{};
};

}  // namespace ntk::scheduler
