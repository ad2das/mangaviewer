#include "ntk_fixed_depth_one_scheduler.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace ntk::scheduler {

void FixedOpportunityGate::reset() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    armed_work_generation_ = 0;
    armed_reservation_sequence_ = 0;
    last_consumed_opportunity_ = 0;
    pending_.reset();
    in_flight_.reset();
}

bool FixedOpportunityGate::arm(
        std::uint64_t workGeneration,
        std::uint64_t reservationSequence) noexcept {
    if (workGeneration == 0 || reservationSequence == 0) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    if (armed_work_generation_ != 0 || armed_reservation_sequence_ != 0 ||
        pending_.has_value() || in_flight_.has_value()) return false;
    armed_work_generation_ = workGeneration;
    armed_reservation_sequence_ = reservationSequence;
    last_consumed_opportunity_ = 0;
    return true;
}

FixedOpportunityGate::PublishResult FixedOpportunityGate::publish(
        const FixedOpportunityIdentity& identity) noexcept {
    if (!identity.valid()) return PublishResult::INVALID;
    std::lock_guard<std::mutex> lock(mutex_);
    if (identity.work_generation != armed_work_generation_ ||
        identity.reservation_sequence != armed_reservation_sequence_) {
        return PublishResult::FOREIGN;
    }
    if ((pending_.has_value() && *pending_ == identity) ||
        (in_flight_.has_value() && *in_flight_ == identity)) {
        return PublishResult::DUPLICATE;
    }
    if (identity.opportunity_sequence <= last_consumed_opportunity_ ||
        (in_flight_.has_value() &&
         identity.opportunity_sequence <=
             in_flight_->opportunity_sequence) ||
        (pending_.has_value() &&
         identity.opportunity_sequence <= pending_->opportunity_sequence)) {
        return PublishResult::STALE;
    }
    if (pending_.has_value()) return PublishResult::OVERFLOW;
    const std::uint64_t predecessor = in_flight_.has_value()
        ? in_flight_->opportunity_sequence : last_consumed_opportunity_;
    if (predecessor != 0 &&
        identity.opportunity_sequence != predecessor + 1) {
        return PublishResult::OVERFLOW;
    }
    pending_ = identity;
    return PublishResult::PUBLISHED;
}

std::optional<FixedOpportunityIdentity>
FixedOpportunityGate::beginReadyAttempt() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (in_flight_.has_value() || !pending_.has_value()) return std::nullopt;
    in_flight_ = *pending_;
    pending_.reset();
    return in_flight_;
}

bool FixedOpportunityGate::finishConsumed(
        const FixedOpportunityIdentity& identity) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!in_flight_.has_value() || !(*in_flight_ == identity)) return false;
    last_consumed_opportunity_ = identity.opportunity_sequence;
    in_flight_.reset();
    return true;
}

void FixedOpportunityGate::cancelArmedReservation() noexcept {
    reset();
}

bool FixedOpportunityGate::hasPending() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return pending_.has_value();
}

bool FixedOpportunityGate::attemptInFlight() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return in_flight_.has_value();
}

std::uint64_t FixedOpportunityGate::lastConsumedOpportunity() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_consumed_opportunity_;
}

namespace {

void mergeRange(
        std::int64_t value,
        std::int64_t* oldest,
        std::int64_t* newest) noexcept {
    if (value <= 0 || oldest == nullptr || newest == nullptr) return;
    if (*oldest == 0 || value < *oldest) *oldest = value;
    *newest = std::max(*newest, value);
}

}  // namespace

bool InputEnvelope::ordered() const noexcept {
    return input_watermark > 0 && event_oldest_ns > 0 &&
        event_newest_ns >= event_oldest_ns && main_ingress_oldest_ns > 0 &&
        main_ingress_newest_ns >= main_ingress_oldest_ns &&
        receipt_oldest_ns > 0 && receipt_newest_ns >= receipt_oldest_ns &&
        mutation_oldest_ns > 0 && mutation_newest_ns >= mutation_oldest_ns;
}

void InputEnvelope::recordInput(
        std::uint64_t sequence,
        std::int64_t eventNanos,
        std::int64_t mainIngressNanos,
        std::int64_t receiptNanos) noexcept {
    input_watermark = std::max(input_watermark, sequence);
    mergeRange(eventNanos, &event_oldest_ns, &event_newest_ns);
    mergeRange(
        mainIngressNanos, &main_ingress_oldest_ns,
        &main_ingress_newest_ns);
    mergeRange(receiptNanos, &receipt_oldest_ns, &receipt_newest_ns);
}

void InputEnvelope::recordMutation(std::int64_t mutationNanos) noexcept {
    mergeRange(mutationNanos, &mutation_oldest_ns, &mutation_newest_ns);
}

void InputEnvelope::merge(const InputEnvelope& other) noexcept {
    input_watermark = std::max(input_watermark, other.input_watermark);
    mergeRange(other.event_oldest_ns, &event_oldest_ns, &event_newest_ns);
    mergeRange(other.event_newest_ns, &event_oldest_ns, &event_newest_ns);
    mergeRange(
        other.main_ingress_oldest_ns,
        &main_ingress_oldest_ns, &main_ingress_newest_ns);
    mergeRange(
        other.main_ingress_newest_ns,
        &main_ingress_oldest_ns, &main_ingress_newest_ns);
    mergeRange(
        other.receipt_oldest_ns, &receipt_oldest_ns, &receipt_newest_ns);
    mergeRange(
        other.receipt_newest_ns, &receipt_oldest_ns, &receipt_newest_ns);
    mergeRange(
        other.mutation_oldest_ns, &mutation_oldest_ns,
        &mutation_newest_ns);
    mergeRange(
        other.mutation_newest_ns, &mutation_oldest_ns,
        &mutation_newest_ns);
}

void FixedDepthOneScheduler::reset(const ViewState& initialView) noexcept {
    reducer_ = {};
    reducer_.view = initialView;
    successor_.reset();
    visual_demand_epoch_counter_ = 0;
    visual_mutation_serial_ = 0;
    head_present_ = false;
    {
        std::lock_guard<std::mutex> lock(terminal_mutex_);
        terminals_ = {};
        terminal_write_index_ = 0;
        counters_ = {};
    }
}

void FixedDepthOneScheduler::recordInput(const InputSample& input) noexcept {
    reducer_.unassigned_input.recordInput(
        input.input_sequence, input.event_time_ns,
        input.main_ingress_ns, input.receipt_time_ns);
    reducer_.applied_move_sequence = std::max(
        reducer_.applied_move_sequence, input.input_sequence);
}

bool FixedDepthOneScheduler::applyScroll(
        const InputSample& input,
        std::int64_t maximumScroll,
        std::int64_t mutationNanos) noexcept {
    if (reducer_.gesture_state != ReducerGestureState::ACTIVE ||
        input.gesture_generation != reducer_.gesture_generation ||
        input.pointer_id != reducer_.active_pointer_id) return false;
    const double delta = static_cast<double>(reducer_.last_touch_y) -
        static_cast<double>(input.y) + reducer_.fractional_scroll_remainder;
    const std::int64_t previousScroll = reducer_.view.scroll_top;
    const std::int64_t integralDelta = static_cast<std::int64_t>(
        std::llround(delta));
    const std::int64_t requested = previousScroll + integralDelta;
    const std::int64_t next = std::clamp<std::int64_t>(
        requested,
        0, std::max<std::int64_t>(0, maximumScroll));
    reducer_.fractional_scroll_remainder = requested == next
        ? delta - static_cast<double>(integralDelta)
        : 0.0;
    const std::int64_t previousEvent = reducer_.last_event_time_ns;
    reducer_.last_touch_y = input.y;
    reducer_.last_event_time_ns = input.event_time_ns;
    recordInput(input);
    if (next == previousScroll) return false;
    reducer_.view.scroll_direction = next > previousScroll ? 1 : -1;
    if (previousEvent > 0 && input.event_time_ns > previousEvent) {
        const double seconds = static_cast<double>(
            input.event_time_ns - previousEvent) / 1'000'000'000.0;
        reducer_.view.velocity_px_per_second = static_cast<float>(
            static_cast<double>(next - previousScroll) / seconds);
    }
    reducer_.view.scroll_top = next;
    ++visual_mutation_serial_;
    if (visual_mutation_serial_ == 0) ++visual_mutation_serial_;
    reducer_.unassigned_input.recordMutation(mutationNanos);
    return true;
}

ReductionResult FixedDepthOneScheduler::reduceMove(
        const InputSample& input,
        std::int64_t maximumScroll,
        std::int64_t mutationNanos) noexcept {
    ReductionResult result{};
    result.valid = reducer_.gesture_state == ReducerGestureState::ACTIVE &&
        input.gesture_generation == reducer_.gesture_generation &&
        input.pointer_id == reducer_.active_pointer_id &&
        input.input_sequence > reducer_.applied_move_sequence;
    if (!result.valid) return result;
    result.frame_cause = applyScroll(input, maximumScroll, mutationNanos);
    return result;
}

ReductionResult FixedDepthOneScheduler::reduceControl(
        const InputSample& input,
        std::int64_t maximumScroll,
        std::int64_t mutationNanos) noexcept {
    constexpr int kDown = 0;
    constexpr int kUp = 1;
    constexpr int kCancel = 3;
    ReductionResult result{};
    if (input.action == kDown) {
        if (reducer_.gesture_state != ReducerGestureState::IDLE ||
            successorTerminal()) return result;
        reducer_.gesture_state = ReducerGestureState::ACTIVE;
        reducer_.gesture_generation = input.gesture_generation;
        reducer_.active_pointer_id = input.pointer_id;
        reducer_.last_touch_y = input.y;
        reducer_.fractional_scroll_remainder = 0.0;
        reducer_.last_event_time_ns = input.event_time_ns;
        reducer_.view.velocity_px_per_second = 0.0F;
        reducer_.applied_move_sequence = input.input_sequence;
        reducer_.visual_demand_epoch = ++visual_demand_epoch_counter_;
        if (reducer_.visual_demand_epoch == 0) {
            reducer_.visual_demand_epoch = ++visual_demand_epoch_counter_;
        }
        recordInput(input);
        result.valid = true;
        return result;
    }
    if (input.action != kUp && input.action != kCancel) return result;
    if (reducer_.gesture_state != ReducerGestureState::ACTIVE ||
        input.gesture_generation != reducer_.gesture_generation ||
        input.pointer_id != reducer_.active_pointer_id) return result;
    if (input.terminal_move.valid &&
        input.terminal_move.input_sequence > reducer_.applied_move_sequence) {
        InputSample terminalMove{};
        terminalMove.action = 2;
        terminalMove.event_time_ns = input.terminal_move.event_time_ns;
        terminalMove.main_ingress_ns = input.terminal_move.main_ingress_ns;
        terminalMove.receipt_time_ns = input.terminal_move.receipt_time_ns;
        terminalMove.input_sequence = input.terminal_move.input_sequence;
        terminalMove.gesture_generation = input.terminal_move.gesture_generation;
        terminalMove.x = input.terminal_move.x;
        terminalMove.y = input.terminal_move.y;
        terminalMove.pointer_id = input.terminal_move.pointer_id;
        (void)applyScroll(terminalMove, maximumScroll, mutationNanos);
    }
    // Android drag widgets consume displacement on MOVE. ACTION_UP/CANCEL terminates the
    // gesture and contributes velocity/ownership evidence, but does not move the viewport to a
    // slightly different release coordinate. Applying UP here caused the visible strip to nudge
    // by a few pixels after the finger had already stopped.
    reducer_.last_touch_y = input.y;
    reducer_.last_event_time_ns = input.event_time_ns;
    recordInput(input);
    reducer_.fractional_scroll_remainder = 0.0;
    reducer_.unassigned_input.recordMutation(mutationNanos);
    reducer_.gesture_state = ReducerGestureState::IDLE;
    reducer_.active_pointer_id = -1;
    reducer_.last_event_time_ns = input.event_time_ns;
    result.valid = true;
    result.frame_cause = true;
    result.terminal = true;
    result.terminal_input_sequence = input.input_sequence;
    return result;
}

bool FixedDepthOneScheduler::foldReduction(
        const ReductionResult& result,
        const FrameScope& scope,
        std::uint64_t workGeneration,
        const ViewState& predecessorScheduledView) noexcept {
    if (!result.valid || !result.frame_cause || workGeneration == 0 ||
        !reducer_.unassigned_input.hasInput() ||
        reducer_.visual_demand_epoch == 0) return false;
    if (!successor_.has_value()) {
        SuccessorFrameWork successor{};
        successor.work_generation = workGeneration;
        successor.scope = scope;
        successor.kind = result.terminal
            ? FrameKind::TERMINAL : FrameKind::MOVE;
        successor.terminal = result.terminal;
        successor.gesture_generation = reducer_.gesture_generation;
        successor.terminal_input_sequence = result.terminal_input_sequence;
        successor.view_state = reducer_.view;
        successor.input = reducer_.unassigned_input;
        successor.visual_demand_epoch = reducer_.visual_demand_epoch;
        successor.visual_mutation_serial = visual_mutation_serial_;
        successor.predecessor_scheduled_view = predecessorScheduledView;
        successor.visible_state_changed =
            successor.view_state.scroll_top !=
            predecessorScheduledView.scroll_top;
        successor_ = successor;
    } else {
        SuccessorFrameWork& successor = *successor_;
        if (successor.terminal || successor.scope.surface_epoch != scope.surface_epoch ||
            successor.scope.authority_generation != scope.authority_generation ||
            successor.scope.authority != scope.authority ||
            successor.scope.scene_version != scope.scene_version ||
            successor.gesture_generation != reducer_.gesture_generation) return false;
        successor.input.merge(reducer_.unassigned_input);
        successor.view_state = reducer_.view;
        successor.visual_mutation_serial = visual_mutation_serial_;
        successor.visible_state_changed =
            successor.view_state.scroll_top !=
            successor.predecessor_scheduled_view.scroll_top;
        if (result.terminal) {
            successor.kind = FrameKind::TERMINAL;
            successor.terminal = true;
            successor.terminal_input_sequence = result.terminal_input_sequence;
        }
    }
    reducer_.unassigned_input.clear();
    if (result.terminal && !markTerminalQueued(
            reducer_.gesture_generation,
            result.terminal_input_sequence,
            successor_->work_generation)) return false;
    updateDepthCounters();
    return true;
}

bool FixedDepthOneScheduler::queueStage(
        const FrameScope& scope,
        std::uint64_t workGeneration,
        const ViewState& view) noexcept {
    if (workGeneration == 0 || head_present_ || successor_.has_value()) {
        return false;
    }
    SuccessorFrameWork stage{};
    stage.work_generation = workGeneration;
    stage.scope = scope;
    stage.kind = FrameKind::STAGE;
    stage.view_state = view;
    stage.predecessor_scheduled_view = view;
    successor_ = stage;
    updateDepthCounters();
    return true;
}

std::optional<SuccessorFrameWork>
FixedDepthOneScheduler::promoteSuccessor() noexcept {
    if (!successor_.has_value() || head_present_) return std::nullopt;
    if (successor_->terminal && !markTerminalPrepared(
            successor_->gesture_generation,
            successor_->terminal_input_sequence,
            successor_->work_generation)) return std::nullopt;
    std::optional<SuccessorFrameWork> promoted = std::move(successor_);
    successor_.reset();
    head_present_ = true;
    updateDepthCounters();
    return promoted;
}

void FixedDepthOneScheduler::discardProducerWork() noexcept {
    successor_.reset();
    head_present_ = false;
    reducer_.gesture_state = ReducerGestureState::IDLE;
    reducer_.active_pointer_id = -1;
    reducer_.last_event_time_ns = 0;
    reducer_.unassigned_input.clear();
    updateDepthCounters();
}

void FixedDepthOneScheduler::noteHeadPresent(bool present) noexcept {
    head_present_ = present;
    updateDepthCounters();
}

void FixedDepthOneScheduler::noteSwappyReservationDepth(
        std::uint64_t depth) noexcept {
    std::lock_guard<std::mutex> lock(terminal_mutex_);
    counters_.max_swappy_reservation_depth = std::max(
        counters_.max_swappy_reservation_depth, depth);
}

void FixedDepthOneScheduler::noteBackendPreparedDepth(
        std::uint64_t depth) noexcept {
    std::lock_guard<std::mutex> lock(terminal_mutex_);
    counters_.max_backend_prepared_depth = std::max(
        counters_.max_backend_prepared_depth, depth);
}

void FixedDepthOneScheduler::noteSpuriousCommitAttempt() noexcept {
    std::lock_guard<std::mutex> lock(terminal_mutex_);
    ++counters_.spurious_commit_attempt_count;
}

#define NTK_DEFINE_COUNTER_NOTE(methodName, fieldName) \
    void FixedDepthOneScheduler::methodName() noexcept { \
        std::lock_guard<std::mutex> lock(terminal_mutex_); \
        ++counters_.fieldName; \
    }

NTK_DEFINE_COUNTER_NOTE(
    noteMatchingJoinOpenPublish, matching_join_open_publish_count)
NTK_DEFINE_COUNTER_NOTE(
    noteDuplicateJoinOpen, duplicate_join_open_count)
NTK_DEFINE_COUNTER_NOTE(noteForeignNotice, foreign_notice_count)
NTK_DEFINE_COUNTER_NOTE(
    noteCandidateNoticeIgnored, candidate_notice_ignored_count)
NTK_DEFINE_COUNTER_NOTE(
    noteReadinessDeferredOpportunity, readiness_deferred_opportunity_count)
NTK_DEFINE_COUNTER_NOTE(
    noteOpportunityConsumedClosed, opportunity_consumed_closed_count)
NTK_DEFINE_COUNTER_NOTE(
    noteOpportunityConsumedSubmitted, opportunity_consumed_submitted_count)
NTK_DEFINE_COUNTER_NOTE(
    noteOpportunityProtocolFatal, opportunity_protocol_fatal_count)

#undef NTK_DEFINE_COUNTER_NOTE

void FixedDepthOneScheduler::updateDepthCounters() noexcept {
    const std::uint64_t successorDepth = successor_.has_value() ? 1U : 0U;
    const std::uint64_t headDepth = head_present_ ? 1U : 0U;
    std::lock_guard<std::mutex> lock(terminal_mutex_);
    counters_.max_successor_depth = std::max(
        counters_.max_successor_depth, successorDepth);
    counters_.max_logical_producer_depth = std::max(
        counters_.max_logical_producer_depth, headDepth + successorDepth);
}

bool FixedDepthOneScheduler::appendAcceptedTerminal(
        std::uint64_t gestureGeneration,
        std::uint64_t inputSequence) noexcept {
    if (gestureGeneration == 0 || inputSequence == 0) return false;
    std::lock_guard<std::mutex> lock(terminal_mutex_);
    for (const auto& entry : terminals_) {
        if (entry.has_value() &&
            entry->gesture_generation == gestureGeneration &&
            entry->input_sequence == inputSequence) return false;
    }
    auto& slot = terminals_[terminal_write_index_ % terminals_.size()];
    if (slot.has_value() && slot->state != TerminalObligationState::JOINED &&
        slot->state != TerminalObligationState::LOST) return false;
    slot = TerminalObligation{
        .gesture_generation = gestureGeneration,
        .input_sequence = inputSequence,
        .work_generation = 0,
        .state = TerminalObligationState::ACCEPTED,
    };
    terminal_write_index_ = (terminal_write_index_ + 1) % terminals_.size();
    ++counters_.terminal_accepted_count;
    return true;
}

bool FixedDepthOneScheduler::transitionTerminal(
        std::uint64_t gestureGeneration,
        std::uint64_t inputSequence,
        std::uint64_t workGeneration,
        TerminalObligationState expected,
        TerminalObligationState next) noexcept {
    std::lock_guard<std::mutex> lock(terminal_mutex_);
    for (auto& entry : terminals_) {
        if (!entry.has_value() ||
            entry->gesture_generation != gestureGeneration ||
            entry->input_sequence != inputSequence ||
            entry->state != expected) continue;
        if (expected != TerminalObligationState::ACCEPTED &&
            entry->work_generation != workGeneration) return false;
        if (expected == TerminalObligationState::ACCEPTED) {
            if (workGeneration == 0) return false;
            entry->work_generation = workGeneration;
        }
        entry->state = next;
        if (next == TerminalObligationState::QUEUED_IN_SUCCESSOR) {
            ++counters_.terminal_queued_count;
        } else if (next == TerminalObligationState::PREPARED) {
            ++counters_.terminal_prepared_count;
        } else if (next == TerminalObligationState::SUBMITTED) {
            ++counters_.terminal_submitted_count;
        } else if (next == TerminalObligationState::JOINED) {
            ++counters_.terminal_joined_count;
        }
        return true;
    }
    return false;
}

bool FixedDepthOneScheduler::markTerminalQueued(
        std::uint64_t gestureGeneration,
        std::uint64_t inputSequence,
        std::uint64_t workGeneration) noexcept {
    return transitionTerminal(
        gestureGeneration, inputSequence, workGeneration,
        TerminalObligationState::ACCEPTED,
        TerminalObligationState::QUEUED_IN_SUCCESSOR);
}

bool FixedDepthOneScheduler::markTerminalPrepared(
        std::uint64_t gestureGeneration,
        std::uint64_t inputSequence,
        std::uint64_t workGeneration) noexcept {
    return transitionTerminal(
        gestureGeneration, inputSequence, workGeneration,
        TerminalObligationState::QUEUED_IN_SUCCESSOR,
        TerminalObligationState::PREPARED);
}

bool FixedDepthOneScheduler::markTerminalSubmitted(
        std::uint64_t gestureGeneration,
        std::uint64_t inputSequence,
        std::uint64_t workGeneration) noexcept {
    return transitionTerminal(
        gestureGeneration, inputSequence, workGeneration,
        TerminalObligationState::PREPARED,
        TerminalObligationState::SUBMITTED);
}

bool FixedDepthOneScheduler::markTerminalJoined(
        std::uint64_t gestureGeneration,
        std::uint64_t inputSequence,
        std::uint64_t workGeneration) noexcept {
    return transitionTerminal(
        gestureGeneration, inputSequence, workGeneration,
        TerminalObligationState::SUBMITTED,
        TerminalObligationState::JOINED);
}

std::uint64_t FixedDepthOneScheduler::markOutstandingTerminalsLost() noexcept {
    std::lock_guard<std::mutex> lock(terminal_mutex_);
    std::uint64_t lost = 0;
    for (auto& entry : terminals_) {
        if (!entry.has_value() ||
            entry->state == TerminalObligationState::JOINED ||
            entry->state == TerminalObligationState::LOST) continue;
        entry->state = TerminalObligationState::LOST;
        ++lost;
    }
    counters_.terminal_lost_count += lost;
    return lost;
}

std::uint64_t FixedDepthOneScheduler::markUnsubmittedTerminalsLost() noexcept {
    std::lock_guard<std::mutex> lock(terminal_mutex_);
    std::uint64_t lost = 0;
    for (auto& entry : terminals_) {
        if (!entry.has_value() ||
            entry->state == TerminalObligationState::SUBMITTED ||
            entry->state == TerminalObligationState::JOINED ||
            entry->state == TerminalObligationState::LOST) continue;
        entry->state = TerminalObligationState::LOST;
        ++lost;
    }
    counters_.terminal_lost_count += lost;
    return lost;
}

bool FixedDepthOneScheduler::hasUnjoinedTerminalObligation() const noexcept {
    std::lock_guard<std::mutex> lock(terminal_mutex_);
    for (const auto& entry : terminals_) {
        if (entry.has_value() &&
            entry->state != TerminalObligationState::JOINED &&
            entry->state != TerminalObligationState::LOST) return true;
    }
    return false;
}

bool FixedDepthOneScheduler::hasRunnableTerminalObligation() const noexcept {
    std::lock_guard<std::mutex> lock(terminal_mutex_);
    for (const auto& entry : terminals_) {
        if (!entry.has_value()) continue;
        if (entry->state == TerminalObligationState::ACCEPTED ||
            entry->state == TerminalObligationState::QUEUED_IN_SUCCESSOR ||
            entry->state == TerminalObligationState::PREPARED) return true;
    }
    return false;
}

bool FixedDepthOneScheduler::terminalConservationExact() const noexcept {
    std::lock_guard<std::mutex> lock(terminal_mutex_);
    std::uint64_t outstanding = 0;
    for (const auto& entry : terminals_) {
        if (!entry.has_value()) continue;
        if (entry->state != TerminalObligationState::JOINED &&
            entry->state != TerminalObligationState::LOST) ++outstanding;
    }
    return counters_.terminal_accepted_count ==
        counters_.terminal_joined_count +
        counters_.terminal_lost_count + outstanding;
}

bool FixedDepthOneScheduler::normalTerminalConservationExact() const noexcept {
    std::lock_guard<std::mutex> lock(terminal_mutex_);
    return counters_.terminal_lost_count == 0 &&
        counters_.terminal_accepted_count == counters_.terminal_submitted_count &&
        counters_.terminal_submitted_count == counters_.terminal_joined_count;
}

SchedulerCounters FixedDepthOneScheduler::counters() const noexcept {
    std::lock_guard<std::mutex> lock(terminal_mutex_);
    return counters_;
}

}  // namespace ntk::scheduler
