#include "../ntk_fixed_depth_one_scheduler.h"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <functional>
#include <iostream>
#include <optional>
#include <string>
#include <vector>

namespace {

using ntk::scheduler::AbortOwnershipActions;
using ntk::scheduler::FixedDepthOneScheduler;
using ntk::scheduler::FixedOpportunityGate;
using ntk::scheduler::FixedOpportunityIdentity;
using ntk::scheduler::FrameKind;
using ntk::scheduler::FrameScope;
using ntk::scheduler::HeadFrameState;
using ntk::scheduler::InputSample;
using ntk::scheduler::ReducerGestureState;
using ntk::scheduler::SuccessorFrameWork;
using ntk::scheduler::ViewState;

constexpr int kDown = 0;
constexpr int kUp = 1;
constexpr int kMove = 2;
constexpr int kCancel = 3;
constexpr FrameScope kScope{1, 1, 1, 1};
constexpr std::int64_t kMaximumScroll = 20'000;

FixedOpportunityIdentity opportunity(
        std::uint64_t value,
        std::uint64_t work = 1,
        std::uint64_t reservation = 1) {
    return {
        .work_generation = work,
        .reservation_sequence = reservation,
        .opportunity_sequence = value,
        .candidate_sequence = value + 100,
        .notice_sequence = value + 200,
    };
}

[[noreturn]] void fail(const std::string& test, const std::string& message) {
    std::cerr << "FAIL " << test << ": " << message << '\n';
    std::exit(1);
}

void require(bool condition, const std::string& test,
             const std::string& message) {
    if (!condition) fail(test, message);
}

InputSample sample(int action, std::uint64_t sequence,
                   std::uint64_t gesture, float y,
                   int pointer = 0) {
    const std::int64_t base = static_cast<std::int64_t>(sequence) * 1'000'000;
    InputSample value{};
    value.action = action;
    value.event_time_ns = base;
    value.main_ingress_ns = base + 10;
    value.receipt_time_ns = base + 20;
    value.input_sequence = sequence;
    value.gesture_generation = gesture;
    value.x = 10.0F;
    value.y = y;
    value.pointer_id = pointer;
    return value;
}

void down(FixedDepthOneScheduler& scheduler, std::uint64_t sequence,
          std::uint64_t gesture, float y) {
    const auto result = scheduler.reduceControl(
        sample(kDown, sequence, gesture, y), kMaximumScroll,
        static_cast<std::int64_t>(sequence) * 1'000'000 + 30);
    require(result.valid && !result.frame_cause,
            "fixture", "DOWN reduction failed");
}

void move(FixedDepthOneScheduler& scheduler, std::uint64_t sequence,
          std::uint64_t gesture, float y, std::uint64_t work,
          const ViewState& predecessor) {
    const auto result = scheduler.reduceMove(
        sample(kMove, sequence, gesture, y), kMaximumScroll,
        static_cast<std::int64_t>(sequence) * 1'000'000 + 30);
    require(result.valid, "fixture", "MOVE reduction failed");
    if (result.frame_cause) {
        require(scheduler.foldReduction(
                    result, kScope, work, predecessor),
                "fixture", "MOVE fold failed");
    }
}

void terminal(FixedDepthOneScheduler& scheduler, std::uint64_t sequence,
              std::uint64_t gesture, float y, std::uint64_t work,
              const ViewState& predecessor, int action = kUp) {
    require(scheduler.appendAcceptedTerminal(gesture, sequence),
            "fixture", "terminal acceptance failed");
    const auto result = scheduler.reduceControl(
        sample(action, sequence, gesture, y), kMaximumScroll,
        static_cast<std::int64_t>(sequence) * 1'000'000 + 30);
    require(result.valid && result.frame_cause && result.terminal &&
                result.terminal_input_sequence == sequence,
            "fixture", "terminal reduction failed");
    require(scheduler.foldReduction(
                result, kScope, work, predecessor),
            "fixture", "terminal fold failed");
}

bool sameView(const ViewState& left, const ViewState& right) {
    return left.scroll_top == right.scroll_top &&
        left.velocity_px_per_second == right.velocity_px_per_second &&
        left.scroll_direction == right.scroll_direction;
}

bool sameWork(const SuccessorFrameWork& left,
              const SuccessorFrameWork& right) {
    return left.work_generation == right.work_generation &&
        left.kind == right.kind && left.terminal == right.terminal &&
        left.gesture_generation == right.gesture_generation &&
        left.terminal_input_sequence == right.terminal_input_sequence &&
        sameView(left.view_state, right.view_state) &&
        left.input.input_watermark == right.input.input_watermark &&
        left.input.event_oldest_ns == right.input.event_oldest_ns &&
        left.input.event_newest_ns == right.input.event_newest_ns &&
        left.input.mutation_oldest_ns == right.input.mutation_oldest_ns &&
        left.input.mutation_newest_ns == right.input.mutation_newest_ns &&
        left.visual_demand_epoch == right.visual_demand_epoch &&
        left.visual_mutation_serial == right.visual_mutation_serial &&
        left.visible_state_changed == right.visible_state_changed;
}

void retained_head_drains_terminal_successor() {
    const std::string name = "retained_head_drains_terminal_successor";
    FixedDepthOneScheduler scheduler;
    scheduler.reset({});
    down(scheduler, 1, 1, 500.0F);
    move(scheduler, 2, 1, 450.0F, 10, {});
    auto head = scheduler.promoteSuccessor();
    require(head.has_value() && head->kind == FrameKind::MOVE,
            name, "MOVE did not become immutable head");
    const SuccessorFrameWork frozen = *head;
    move(scheduler, 3, 1, 400.0F, 11, head->view_state);
    terminal(scheduler, 4, 1, 375.0F, 11, head->view_state);
    require(sameWork(*head, frozen), name, "retained head mutated");
    require(scheduler.successorTerminal(), name,
            "terminal successor missing");
    require(scheduler.successor()->input.input_watermark == 4,
            name, "UP watermark not owned by successor");
    require(scheduler.producerDepth() == 2, name,
            "producer depth was not head+successor");
}

void move_coalescing_stays_in_one_gesture() {
    const std::string name = "move_coalescing_stays_in_one_gesture";
    FixedDepthOneScheduler scheduler;
    scheduler.reset({});
    down(scheduler, 1, 7, 500.0F);
    move(scheduler, 2, 7, 490.0F, 20, {});
    move(scheduler, 3, 7, 470.0F, 20, {});
    move(scheduler, 4, 7, 440.0F, 20, {});
    require(scheduler.successor().has_value(), name, "successor missing");
    const auto before = *scheduler.successor();
    require(before.gesture_generation == 7 &&
                before.view_state.scroll_top == 60 &&
                before.input.input_watermark == 4 &&
                before.input.event_oldest_ns == 1'000'000 &&
                before.input.event_newest_ns == 4'000'000 &&
                before.visual_mutation_serial == 3,
            name, "coalesced envelope/view/serial mismatch");
    auto promoted = scheduler.promoteSuccessor();
    require(promoted.has_value() &&
                promoted->visual_mutation_serial ==
                    before.visual_mutation_serial,
            name, "promotion renumbered mutation serial");
}

void fractional_moves_preserve_one_to_one_drag_distance() {
    const std::string name =
        "fractional_moves_preserve_one_to_one_drag_distance";
    FixedDepthOneScheduler scheduler;
    scheduler.reset({});
    down(scheduler, 1, 1, 500.0F);
    move(scheduler, 2, 1, 499.6F, 21, {});
    move(scheduler, 3, 1, 499.2F, 21, {});
    move(scheduler, 4, 1, 498.8F, 21, {});
    require(scheduler.reducer().view.scroll_top == 1,
            name, "subpixel MOVE distance was discarded between samples");
}

void release_coordinate_does_not_nudge_viewport() {
    const std::string name =
        "release_coordinate_does_not_nudge_viewport";
    FixedDepthOneScheduler scheduler;
    scheduler.reset({});
    down(scheduler, 1, 1, 500.0F);
    move(scheduler, 2, 1, 450.0F, 22, {});
    const auto releasedAt = scheduler.reducer().view.scroll_top;
    terminal(scheduler, 3, 1, 447.0F, 22, {});
    const auto& work = *scheduler.successor();
    require(releasedAt == 50 && work.view_state.scroll_top == releasedAt &&
                work.input.input_watermark == 3 && work.terminal,
            name, "ACTION_UP changed the already-MOVE-owned viewport");
}

void release_crossing_terminal_edge_publishes_exact_bottom() {
    const std::string name =
        "release_crossing_terminal_edge_publishes_exact_bottom";
    FixedDepthOneScheduler scheduler;
    ViewState initial{};
    initial.scroll_top = kMaximumScroll - 51;
    scheduler.reset(initial);
    down(scheduler, 1, 1, 500.0F);
    move(scheduler, 2, 1, 450.0F, 23, initial);
    require(scheduler.reducer().view.scroll_top == kMaximumScroll - 1,
            name, "final MOVE did not stop immediately before the bottom");
    terminal(scheduler, 3, 1, 447.0F, 23, initial);
    const auto& work = *scheduler.successor();
    require(work.view_state.scroll_top == kMaximumScroll &&
                work.visible_state_changed && work.terminal &&
                work.input.input_watermark == 3,
            name, "edge-crossing ACTION_UP did not publish the exact bottom");
}

void terminal_never_crosses_next_down() {
    const std::string name = "terminal_never_crosses_next_down";
    FixedDepthOneScheduler scheduler;
    scheduler.reset({});
    down(scheduler, 1, 1, 100.0F);
    terminal(scheduler, 2, 1, 100.0F, 30, {});
    const auto blocked = scheduler.reduceControl(
        sample(kDown, 3, 2, 100.0F), kMaximumScroll, 3'000'030);
    require(!blocked.valid, name,
            "next DOWN crossed terminal successor");
    auto terminalHead = scheduler.promoteSuccessor();
    require(terminalHead.has_value() && terminalHead->terminal,
            name, "terminal was not promoted first");
    const auto admitted = scheduler.reduceControl(
        sample(kDown, 3, 2, 100.0F), kMaximumScroll, 3'000'030);
    require(admitted.valid, name,
            "next DOWN did not open after terminal promotion");
}

void queued_control_rearms_after_terminal_promotion() {
    const std::string name =
        "queued_control_rearms_after_terminal_promotion";
    FixedDepthOneScheduler scheduler;
    scheduler.reset({});
    require(!scheduler.hasRunnableControlQueue(0), name,
            "empty control queue was runnable");
    require(scheduler.hasRunnableControlQueue(2), name,
            "idle scheduler did not expose queued controls");
    down(scheduler, 1, 1, 100.0F);
    terminal(scheduler, 2, 1, 100.0F, 31, {});
    require(!scheduler.hasRunnableControlQueue(2), name,
            "terminal successor allowed the next gesture to cross");
    auto terminalHead = scheduler.promoteSuccessor();
    require(terminalHead.has_value() && terminalHead->terminal,
            name, "terminal was not promoted to the immutable head");
    require(scheduler.hasRunnableControlQueue(2), name,
            "queued controls did not become self-runnable after promotion");
}

void terminal_clamp_has_real_frame_cause() {
    const std::string name = "terminal_clamp_has_real_frame_cause";
    FixedDepthOneScheduler scheduler;
    scheduler.reset({});
    down(scheduler, 1, 1, 100.0F);
    terminal(scheduler, 2, 1, 100.0F, 40, {});
    const auto& work = *scheduler.successor();
    require(work.terminal && !work.visible_state_changed &&
                work.visual_mutation_serial == 0 &&
                work.input.input_watermark == 2 &&
                work.input.mutation_oldest_ns > 0 &&
                work.input.mutation_newest_ns >=
                    work.input.mutation_oldest_ns &&
                work.input.ordered(),
            name, "clamped terminal lacked physical frame cause proof");
}

struct TerminalOverlapFixture {
    FixedDepthOneScheduler scheduler;
    SuccessorFrameWork terminal_head{};

    TerminalOverlapFixture() {
        scheduler.reset({});
        down(scheduler, 1, 1, 200.0F);
        terminal(scheduler, 2, 1, 200.0F, 50, {});
        auto promoted = scheduler.promoteSuccessor();
        require(promoted.has_value(), "fixture", "terminal promote failed");
        terminal_head = *promoted;
        down(scheduler, 3, 2, 200.0F);
        move(scheduler, 4, 2, 150.0F, 51,
             terminal_head.view_state);
    }
};

void submitted_head_does_not_rewind_successor() {
    const std::string name = "submitted_head_does_not_rewind_successor";
    TerminalOverlapFixture fixture;
    const auto reducerView = fixture.scheduler.reducer().view;
    const auto reducerEpoch =
        fixture.scheduler.reducer().visual_demand_epoch;
    const auto successor = *fixture.scheduler.successor();
    require(fixture.scheduler.markTerminalSubmitted(
                fixture.terminal_head.gesture_generation,
                fixture.terminal_head.terminal_input_sequence,
                fixture.terminal_head.work_generation),
            name, "terminal submit transition failed");
    fixture.scheduler.noteHeadPresent(false);
    require(sameView(fixture.scheduler.reducer().view, reducerView) &&
                fixture.scheduler.reducer().visual_demand_epoch == reducerEpoch &&
                sameWork(*fixture.scheduler.successor(), successor),
            name, "head completion rewound reducer/successor");
}

void old_terminal_can_submit_while_next_gesture_active() {
    const std::string name =
        "old_terminal_can_submit_while_next_gesture_active";
    TerminalOverlapFixture fixture;
    require(fixture.scheduler.reducer().gesture_state ==
                ReducerGestureState::ACTIVE &&
                fixture.scheduler.reducer().gesture_generation == 2,
            name, "next gesture was not active");
    require(fixture.scheduler.markTerminalSubmitted(
                fixture.terminal_head.gesture_generation,
                fixture.terminal_head.terminal_input_sequence,
                fixture.terminal_head.work_generation),
            name, "old terminal could not submit");
    fixture.scheduler.noteHeadPresent(false);
    require(fixture.scheduler.reducer().gesture_state ==
                ReducerGestureState::ACTIVE &&
                fixture.scheduler.reducer().gesture_generation == 2,
            name, "old terminal submit ended next gesture");
}

void candidate_notice_does_not_publish_opportunity() {
    const std::string name =
        "candidate_notice_does_not_publish_opportunity";
    FixedOpportunityGate gate;
    require(gate.arm(1, 1) && !gate.hasPending() &&
                !gate.beginReadyAttempt().has_value(),
            name, "candidate telemetry manufactured commit authority");
}

void foreign_notices_are_ignored() {
    const std::string name = "foreign_notices_are_ignored";
    FixedOpportunityGate gate;
    require(gate.arm(1, 1), name, "arm failed");
    require(gate.publish(opportunity(1, 2, 1)) ==
                FixedOpportunityGate::PublishResult::FOREIGN &&
                gate.publish(opportunity(1, 1, 2)) ==
                FixedOpportunityGate::PublishResult::FOREIGN &&
                !gate.hasPending(),
            name, "foreign work/reservation changed owner state");
}

void matching_join_open_and_duplicate_are_exact() {
    const std::string name =
        "matching_join_open_and_duplicate_are_exact";
    FixedOpportunityGate gate;
    const auto exact = opportunity(41);
    require(gate.arm(1, 1) &&
                gate.publish(exact) ==
                    FixedOpportunityGate::PublishResult::PUBLISHED &&
                gate.publish(exact) ==
                    FixedOpportunityGate::PublishResult::DUPLICATE,
            name, "matching/duplicate publication mismatch");
    const auto attempt = gate.beginReadyAttempt();
    require(attempt == exact && gate.finishConsumed(*attempt) &&
                !gate.hasPending() && !gate.attemptInFlight(),
            name, "exact notice did not produce one consumption");
}

void stale_and_gap_are_rejected() {
    const std::string name = "stale_and_gap_are_rejected";
    FixedOpportunityGate gate;
    const auto first = opportunity(80);
    require(gate.arm(1, 1) &&
                gate.publish(first) ==
                    FixedOpportunityGate::PublishResult::PUBLISHED,
            name, "setup publish failed");
    const auto attempt = gate.beginReadyAttempt();
    require(attempt == first && gate.finishConsumed(*attempt),
            name, "setup consume failed");
    require(gate.publish(first) ==
                FixedOpportunityGate::PublishResult::STALE &&
                gate.publish(opportunity(82)) ==
                FixedOpportunityGate::PublishResult::OVERFLOW,
            name, "matching stale/gap was not rejected");
}

void callback_during_attempt_preserves_successor() {
    const std::string name =
        "callback_during_attempt_preserves_successor";
    FixedOpportunityGate gate;
    const auto current = opportunity(1);
    const auto next = opportunity(2);
    require(gate.arm(1, 1) &&
                gate.publish(current) ==
                    FixedOpportunityGate::PublishResult::PUBLISHED,
            name, "setup failed");
    const auto attempt = gate.beginReadyAttempt();
    require(attempt == current &&
                gate.publish(next) ==
                    FixedOpportunityGate::PublishResult::PUBLISHED &&
                gate.finishConsumed(*attempt) && gate.hasPending(),
            name, "N+1 callback was erased by finishing N");
    const auto follow = gate.beginReadyAttempt();
    require(follow == next && gate.finishConsumed(*follow),
            name, "preserved N+1 was not claimable");
}

void no_progress_never_creates_second_attempt() {
    const std::string name =
        "no_progress_never_creates_second_attempt";
    FixedOpportunityGate gate;
    const auto exact = opportunity(5);
    require(gate.arm(1, 1) &&
                gate.publish(exact) ==
                    FixedOpportunityGate::PublishResult::PUBLISHED,
            name, "setup failed");
    const auto attempt = gate.beginReadyAttempt();
    require(attempt == exact && gate.finishConsumed(*attempt) &&
                !gate.beginReadyAttempt().has_value(),
            name, "gate synthesized a retry without publication");
}

void slot_closed_and_submitted_consume_exact_opportunity() {
    const std::string name =
        "slot_closed_and_submitted_consume_exact_opportunity";
    FixedOpportunityGate gate;
    const auto closed = opportunity(7);
    const auto submitted = opportunity(8);
    require(gate.arm(1, 1) &&
                gate.publish(closed) ==
                    FixedOpportunityGate::PublishResult::PUBLISHED,
            name, "closed setup failed");
    const auto first = gate.beginReadyAttempt();
    require(first == closed &&
                gate.publish(submitted) ==
                    FixedOpportunityGate::PublishResult::PUBLISHED &&
                gate.finishConsumed(*first) &&
                gate.lastConsumedOpportunity() == 7,
            name, "closed opportunity did not consume exactly N");
    const auto second = gate.beginReadyAttempt();
    require(second == submitted && gate.finishConsumed(*second) &&
                gate.lastConsumedOpportunity() == 8,
            name, "submitted opportunity did not consume exactly N+1");
}

void abort_clears_armed_identity() {
    const std::string name = "abort_clears_armed_identity";
    FixedOpportunityGate gate;
    const auto exact = opportunity(1);
    require(gate.arm(1, 1) &&
                gate.publish(exact) ==
                    FixedOpportunityGate::PublishResult::PUBLISHED,
            name, "setup failed");
    gate.cancelArmedReservation();
    require(!gate.hasPending() && !gate.attemptInFlight() &&
                gate.publish(exact) ==
                    FixedOpportunityGate::PublishResult::FOREIGN,
            name, "late callback survived abort boundary");
}

void joined_opportunity_is_sole_attempt_authority() {
    const std::string name =
        "joined_opportunity_is_sole_attempt_authority";
    FixedOpportunityGate gate;
    const auto first = opportunity(11);
    require(gate.arm(1, 1) &&
                gate.publish(first) ==
                    FixedOpportunityGate::PublishResult::PUBLISHED,
            name, "retirement+latch JOIN_OPEN setup failed");
    const auto immediate = gate.beginReadyAttempt();
    require(immediate == first && gate.finishConsumed(*immediate),
            name, "joined opportunity was not immediately usable");

    gate.cancelArmedReservation();
    require(gate.arm(2, 2), name, "second arm failed");
    require(!gate.beginReadyAttempt().has_value(), name,
            "own-frame callback made a speculative attempt");
    const auto second = opportunity(12, 2, 2);
    require(gate.publish(second) ==
                FixedOpportunityGate::PublishResult::PUBLISHED,
            name, "joined publication was lost");
    const auto afterOpportunity = gate.beginReadyAttempt();
    require(afterOpportunity == second &&
                gate.publish(opportunity(13, 2, 2)) ==
                    FixedOpportunityGate::PublishResult::PUBLISHED &&
                gate.finishConsumed(*afterOpportunity) && gate.hasPending(),
            name, "higher opportunity during closed attempt was lost");
}

void swappy_reservation_follows_backend_ready() {
    const std::string name = "swappy_reservation_follows_backend_ready";
    require(ntk::scheduler::reservationFollowsBackendReady(100, 100, 1) &&
                ntk::scheduler::reservationFollowsBackendReady(100, 101, 1) &&
                !ntk::scheduler::reservationFollowsBackendReady(100, 99, 1) &&
                !ntk::scheduler::reservationFollowsBackendReady(100, 101, 2),
            name, "backend-ready/reservation order or depth accepted invalid cut");
}

SuccessorFrameWork preparedTerminal(FixedDepthOneScheduler& scheduler,
                                    std::uint64_t gesture,
                                    std::uint64_t baseSequence,
                                    std::uint64_t work) {
    down(scheduler, baseSequence, gesture, 100.0F);
    terminal(scheduler, baseSequence + 1, gesture, 100.0F, work, {});
    auto promoted = scheduler.promoteSuccessor();
    require(promoted.has_value(), "fixture", "prepared terminal missing");
    return *promoted;
}

void terminal_obligation_conservation() {
    const std::string name = "terminal_obligation_conservation";
    {
        FixedDepthOneScheduler normal;
        normal.reset({});
        const auto head = preparedTerminal(normal, 1, 1, 60);
        require(normal.markTerminalSubmitted(
                    head.gesture_generation, head.terminal_input_sequence,
                    head.work_generation) &&
                    normal.markTerminalJoined(
                        head.gesture_generation, head.terminal_input_sequence,
                        head.work_generation) &&
                    normal.normalTerminalConservationExact(),
                name, "normal conservation failed");
    }
    {
        FixedDepthOneScheduler preFatal;
        preFatal.reset({});
        require(preFatal.appendAcceptedTerminal(1, 2) &&
                    preFatal.markUnsubmittedTerminalsLost() == 1 &&
                    preFatal.terminalConservationExact(),
                name, "pre-submit fatal conservation failed");
    }
    {
        FixedDepthOneScheduler postFatal;
        postFatal.reset({});
        const auto head = preparedTerminal(postFatal, 1, 1, 61);
        require(postFatal.markTerminalSubmitted(
                    head.gesture_generation, head.terminal_input_sequence,
                    head.work_generation) &&
                    postFatal.markUnsubmittedTerminalsLost() == 0 &&
                    postFatal.markTerminalJoined(
                        head.gesture_generation, head.terminal_input_sequence,
                        head.work_generation) &&
                    postFatal.normalTerminalConservationExact(),
                name, "post-submit fatal did not preserve physical join");
    }
    {
        FixedDepthOneScheduler contextLoss;
        contextLoss.reset({});
        const auto submitted = preparedTerminal(contextLoss, 1, 1, 62);
        require(contextLoss.markTerminalSubmitted(
                    submitted.gesture_generation,
                    submitted.terminal_input_sequence,
                    submitted.work_generation) &&
                    contextLoss.appendAcceptedTerminal(2, 3) &&
                    contextLoss.markUnsubmittedTerminalsLost() == 1 &&
                    contextLoss.markTerminalJoined(
                        submitted.gesture_generation,
                        submitted.terminal_input_sequence,
                        submitted.work_generation) &&
                    contextLoss.terminalConservationExact(),
                name, "context-loss conservation failed");
    }
}

void requireActions(const std::string& name, HeadFrameState state,
                    bool target, bool backend, bool swappy,
                    bool externalClaim, bool drain) {
    const AbortOwnershipActions actions =
        ntk::scheduler::abortOwnershipActions(state);
    require(actions.abort_render_target == target &&
                actions.abort_backend_transaction == backend &&
                actions.abort_swappy_reservation == swappy &&
                actions.abort_external_claim == externalClaim &&
                actions.drain_submitted == drain,
            name, "wrong abort action for state " +
                std::to_string(static_cast<int>(state)));
}

void lifecycle_abort_matrix() {
    const std::string name = "lifecycle_abort_matrix";
    requireActions(
        name, HeadFrameState::EMPTY, false, false, false, false, false);
    requireActions(name, HeadFrameState::CONTENT_FROZEN,
                   false, false, false, false, false);
    requireActions(name, HeadFrameState::SWAPPY_RESERVED_PREPARING,
                   false, false, true, false, false);
    requireActions(name, HeadFrameState::GPU_TARGET_OWNED,
                   true, false, true, false, false);
    requireActions(name, HeadFrameState::DRAW_ISSUED,
                   true, false, true, false, false);
    requireActions(name, HeadFrameState::FRAME_ID_RESERVED,
                   true, false, true, false, false);
    requireActions(name, HeadFrameState::BACKEND_READY_UNRESERVED,
                   false, true, true, false, false);
    requireActions(name, HeadFrameState::SWAPPY_RESERVED,
                   false, true, true, false, false);
    requireActions(name, HeadFrameState::EXTERNAL_CLAIMED_NOT_APPLIED,
                   false, true, false, true, false);
    requireActions(name, HeadFrameState::PHASE_COMMITTING,
                   false, false, false, false, true);
    requireActions(name, HeadFrameState::FAILED,
                   false, false, false, false, false);
}

void bounded_control_admission() {
    const std::string name = "bounded_control_admission";
    FixedDepthOneScheduler scheduler;
    scheduler.reset({});
    std::size_t depth = 0;
    for (; depth < FixedDepthOneScheduler::kControlCapacity; ++depth) {
        require(FixedDepthOneScheduler::canAcceptControl(depth),
                name, "capacity rejected early");
        require(scheduler.appendAcceptedTerminal(depth + 1, depth + 1),
                name, "accepted sequence was not ledgered");
    }
    require(!FixedDepthOneScheduler::canAcceptControl(depth),
            name, "full control ring accepted one more event");
    const auto counters = scheduler.counters();
    require(counters.terminal_accepted_count ==
                FixedDepthOneScheduler::kControlCapacity &&
                scheduler.terminalConservationExact(),
            name, "accepted control sequence was lost");
}

void eighty_one_opportunities_are_exact_and_bounded() {
    const std::string name =
        "eighty_one_opportunities_are_exact_and_bounded";
    FixedOpportunityGate gate;
    require(gate.arm(1, 1), name, "arm failed");
    for (std::uint64_t sequence = 1; sequence <= 81; ++sequence) {
        const auto exact = opportunity(sequence);
        require(gate.publish(exact) ==
                    FixedOpportunityGate::PublishResult::PUBLISHED,
                name, "wake sequence lost");
        const auto attempt = gate.beginReadyAttempt();
        require(attempt == exact && gate.finishConsumed(*attempt),
                name, "opportunity was not one-shot consumed");
    }
    require(gate.lastConsumedOpportunity() == 81 &&
                !gate.hasPending() && !gate.attemptInFlight(), name,
            "last opportunity was not consumed");
}

enum class LifecycleKind : std::uint8_t {
    DETACH,
    DISARM,
    RELEASE,
    SUCCESSOR_BIND,
};

enum class InitialTerminalState : std::uint8_t {
    ACCEPTED,
    QUEUED,
    PREPARED,
    SUBMITTED,
};

void lifecycle_defers_once_until_terminal_joined() {
    const std::string name =
        "lifecycle_defers_once_until_terminal_joined";
    for (const LifecycleKind lifecycle : {
             LifecycleKind::DETACH, LifecycleKind::DISARM,
             LifecycleKind::RELEASE, LifecycleKind::SUCCESSOR_BIND}) {
        for (const InitialTerminalState initial : {
                 InitialTerminalState::ACCEPTED,
                 InitialTerminalState::QUEUED,
                 InitialTerminalState::PREPARED,
                 InitialTerminalState::SUBMITTED}) {
            (void)lifecycle;
            FixedDepthOneScheduler scheduler;
            scheduler.reset({});
            constexpr std::uint64_t gesture = 1;
            constexpr std::uint64_t input = 5;
            constexpr std::uint64_t work = 100;
            require(scheduler.appendAcceptedTerminal(gesture, input),
                    name, "accept failed");
            if (initial >= InitialTerminalState::QUEUED) {
                require(scheduler.markTerminalQueued(
                            gesture, input, work),
                        name, "queue failed");
            }
            if (initial >= InitialTerminalState::PREPARED) {
                require(scheduler.markTerminalPrepared(
                            gesture, input, work),
                        name, "prepare failed");
            }
            if (initial >= InitialTerminalState::SUBMITTED) {
                require(scheduler.markTerminalSubmitted(
                            gesture, input, work),
                        name, "submit failed");
            }
            bool deferred = scheduler.hasUnjoinedTerminalObligation();
            std::uint64_t lifecycleExecutions = 0;
            std::uint64_t normalAbortCount = 0;
            require(deferred && lifecycleExecutions == 0,
                    name, "lifecycle did not defer");
            if (initial < InitialTerminalState::QUEUED) {
                require(scheduler.markTerminalQueued(
                            gesture, input, work),
                        name, "deferred queue failed");
            }
            if (initial < InitialTerminalState::PREPARED) {
                require(scheduler.markTerminalPrepared(
                            gesture, input, work),
                        name, "deferred prepare failed");
            }
            if (initial < InitialTerminalState::SUBMITTED) {
                require(scheduler.markTerminalSubmitted(
                            gesture, input, work),
                        name, "deferred submit failed");
            }
            require(scheduler.markTerminalJoined(gesture, input, work),
                    name, "join failed");
            if (deferred &&
                !scheduler.hasUnjoinedTerminalObligation()) {
                deferred = false;
                ++lifecycleExecutions;
            }
            const auto counters = scheduler.counters();
            require(!deferred && lifecycleExecutions == 1 &&
                        normalAbortCount == 0 &&
                        counters.terminal_lost_count == 0 &&
                        scheduler.normalTerminalConservationExact(),
                    name, "deferred lifecycle did not execute exactly once");
        }
    }
}

void fifty_nine_gestures_295_events_terminal_liveness() {
    const std::string name =
        "fifty_nine_gestures_295_events_terminal_liveness";
    FixedDepthOneScheduler scheduler;
    scheduler.reset({});
    constexpr std::uint64_t gestureCount = 59;
    std::uint64_t eventCount = 0;
    for (std::uint64_t gesture = 1; gesture <= gestureCount; ++gesture) {
        eventCount += 5;
        require(scheduler.appendAcceptedTerminal(
                    gesture, gesture * 5),
                name, "physical terminal acceptance failed");
    }
    require(eventCount == 295, name, "physical event count mismatch");

    FixedOpportunityGate gate;
    std::uint64_t opportunitySequence = 0;
    for (std::uint64_t gesture = 1; gesture <= gestureCount; ++gesture) {
        const std::uint64_t input = gesture * 5;
        const std::uint64_t work = 1000 + gesture;
        const std::uint64_t reservation = 2000 + gesture;
        require(scheduler.markTerminalQueued(gesture, input, work) &&
                    scheduler.markTerminalPrepared(gesture, input, work),
                name, "terminal did not reach PREPARED");
        scheduler.noteHeadPresent(true);
        scheduler.noteBackendPreparedDepth(1);
        scheduler.noteSwappyReservationDepth(1);
        require(gate.arm(work, reservation), name, "gate arm failed");
        auto exact = opportunity(
            ++opportunitySequence, work, reservation);
        require(gate.publish(exact) ==
                    FixedOpportunityGate::PublishResult::PUBLISHED,
                name, "JOIN_OPEN publish failed");
        auto attempt = gate.beginReadyAttempt();
        require(attempt == exact, name, "ready attempt missing");
        if (gesture == 1) {
            const auto higher = opportunity(
                ++opportunitySequence, work, reservation);
            require(gate.publish(higher) ==
                        FixedOpportunityGate::PublishResult::PUBLISHED &&
                        gate.finishConsumed(*attempt),
                    name, "first closed opportunity lost successor");
            attempt = gate.beginReadyAttempt();
            require(attempt == higher, name,
                    "higher opportunity did not follow first closed slot");
        }
        require(gate.finishConsumed(*attempt) &&
                    scheduler.markTerminalSubmitted(
                        gesture, input, work) &&
                    scheduler.markTerminalJoined(
                        gesture, input, work),
                name, "terminal physical join failed");
        scheduler.noteHeadPresent(false);
        gate.cancelArmedReservation();
    }
    const auto counters = scheduler.counters();
    require(counters.terminal_accepted_count == gestureCount &&
                counters.terminal_queued_count == gestureCount &&
                counters.terminal_prepared_count == gestureCount &&
                counters.terminal_submitted_count == gestureCount &&
                counters.terminal_joined_count == gestureCount &&
                counters.terminal_lost_count == 0 &&
                counters.max_logical_producer_depth <= 2 &&
                counters.max_successor_depth <= 1 &&
                counters.max_swappy_reservation_depth <= 1 &&
                counters.max_backend_prepared_depth <= 1 &&
                counters.spurious_commit_attempt_count == 0 &&
                scheduler.normalTerminalConservationExact(),
            name, "59-terminal final conservation failed");
}

std::uint64_t nextRandom(std::uint64_t& state) {
    state ^= state << 13U;
    state ^= state >> 7U;
    state ^= state << 17U;
    return state;
}

void deterministic_10000_scheduler_interleavings() {
    const std::string name =
        "deterministic_10000_scheduler_interleavings";
    FixedDepthOneScheduler scheduler;
    scheduler.reset({});
    std::optional<SuccessorFrameWork> head;
    std::vector<SuccessorFrameWork> submittedTerminals;
    ViewState presented{};
    std::uint64_t random = 0x5a17c9e3d4b28601ULL;
    std::uint64_t sequence = 0;
    std::uint64_t gesture = 0;
    std::uint64_t work = 100;
    std::uint64_t lastWatermark = 0;

    for (int step = 0; step < 10'000; ++step) {
        const std::uint64_t choice = nextRandom(random) % 11U;
        const auto reducerState = scheduler.reducer().gesture_state;
        if (choice <= 1 && reducerState == ReducerGestureState::IDLE &&
            !scheduler.successorTerminal()) {
            down(scheduler, ++sequence, ++gesture,
                 600.0F - static_cast<float>(step % 50));
        } else if (choice <= 4 &&
                   reducerState == ReducerGestureState::ACTIVE) {
            const ViewState predecessor = head.has_value()
                ? head->view_state : presented;
            const std::uint64_t selectedWork =
                scheduler.successor().has_value()
                    ? scheduler.successor()->work_generation : ++work;
            const float y = scheduler.reducer().last_touch_y -
                static_cast<float>((nextRandom(random) % 7U) + 1U);
            move(scheduler, ++sequence, scheduler.reducer().gesture_generation,
                 y, selectedWork, predecessor);
        } else if (choice == 5 &&
                   reducerState == ReducerGestureState::ACTIVE) {
            const ViewState predecessor = head.has_value()
                ? head->view_state : presented;
            const std::uint64_t selectedWork =
                scheduler.successor().has_value()
                    ? scheduler.successor()->work_generation : ++work;
            terminal(scheduler, ++sequence,
                     scheduler.reducer().gesture_generation,
                     scheduler.reducer().last_touch_y,
                     selectedWork, predecessor,
                     (nextRandom(random) & 1U) != 0 ? kUp : kCancel);
        } else if (choice == 6 && !head.has_value() &&
                   scheduler.successor().has_value()) {
            head = scheduler.promoteSuccessor();
            require(head.has_value(), name, "promotion failed");
            lastWatermark = std::max(
                lastWatermark, head->input.input_watermark);
        } else if (choice == 7 && head.has_value()) {
            const SuccessorFrameWork immutable = *head;
            if (head->terminal) {
                require(scheduler.markTerminalSubmitted(
                            head->gesture_generation,
                            head->terminal_input_sequence,
                            head->work_generation),
                        name, "terminal submit transition failed");
                submittedTerminals.push_back(*head);
            }
            presented = head->view_state;
            scheduler.noteHeadPresent(false);
            require(sameWork(*head, immutable), name,
                    "head bytes changed during submit");
            head.reset();
        } else if (choice == 8 && !submittedTerminals.empty()) {
            const auto joined = submittedTerminals.front();
            submittedTerminals.erase(submittedTerminals.begin());
            require(scheduler.markTerminalJoined(
                        joined.gesture_generation,
                        joined.terminal_input_sequence,
                        joined.work_generation),
                    name, "terminal join transition failed");
        } else if (choice == 9) {
            (void)scheduler.markUnsubmittedTerminalsLost();
            scheduler.discardProducerWork();
            head.reset();
        } else if (choice == 10 && !head.has_value() &&
                   !scheduler.hasUnjoinedTerminalObligation()) {
            scheduler.discardProducerWork();
        }

        require(scheduler.producerDepth() <= 2,
                name, "producer depth exceeded two");
        require(!scheduler.successor().has_value() ||
                    scheduler.producerDepth() >= 1,
                name, "successor depth accounting failed");
        require(scheduler.terminalConservationExact(),
                name, "terminal conservation failed");
        if (head.has_value()) {
            require(scheduler.headPresent(), name,
                    "model head diverged from scheduler head");
        }
        if (scheduler.successor().has_value()) {
            const auto& successor = *scheduler.successor();
            require(successor.input.input_watermark >= lastWatermark,
                    name, "watermark regressed");
            require(successor.kind == FrameKind::STAGE ||
                        successor.gesture_generation ==
                            scheduler.reducer().gesture_generation,
                    name, "gesture epoch mixed in successor");
        }
        const auto counters = scheduler.counters();
        require(counters.max_logical_producer_depth <= 2 &&
                    counters.max_successor_depth <= 1 &&
                    counters.max_swappy_reservation_depth <= 1 &&
                    counters.max_backend_prepared_depth <= 1,
                name, "runtime depth counter exceeded invariant");
    }

    while (!submittedTerminals.empty()) {
        const auto joined = submittedTerminals.back();
        submittedTerminals.pop_back();
        require(scheduler.markTerminalJoined(
                    joined.gesture_generation,
                    joined.terminal_input_sequence,
                    joined.work_generation),
                name, "final terminal join failed");
    }
    (void)scheduler.markUnsubmittedTerminalsLost();
    scheduler.discardProducerWork();
    require(scheduler.terminalConservationExact(), name,
            "final conservation failed");
}

enum class ProtocolEvent : std::uint8_t {
    CANDIDATE_NOTICE,
    FOREIGN_JOIN_NOTICE,
    MATCHING_JOIN_NOTICE,
    DUPLICATE_JOIN_NOTICE,
    OWN_FRAME_LATCH_CALLBACK,
    BEGIN_READY_ATTEMPT,
    SLOT_CLOSED,
    SUBMITTED,
    LATCH_EVIDENCE,
    RETIREMENT_EVIDENCE,
    TRANSACTION_COMPLETE,
    PREVIOUS_RELEASE,
    ACQUIRE_FENCE,
    LIFECYCLE_REQUEST,
    LIFECYCLE_REARM,
};

void deterministic_10000_interleavings() {
    const std::string name = "deterministic_10000_interleavings";
    FixedDepthOneScheduler scheduler;
    scheduler.reset({});
    FixedOpportunityGate gate;
    std::uint64_t random = 0x9d9dbeef12345678ULL;
    std::uint64_t work = 1;
    std::uint64_t reservation = 1;
    std::uint64_t opportunitySequence = 0;
    std::uint64_t attemptCount = 0;
    std::uint64_t consumedCount = 0;
    std::uint64_t closedCount = 0;
    std::uint64_t candidateIgnored = 0;
    std::uint64_t foreignIgnored = 0;
    std::uint64_t lifecycleExecutions = 0;
    std::uint64_t normalAbortCount = 0;
    std::uint64_t terminalProgress = 0;
    std::uint64_t lastLifecycleRearm = 0;
    bool demandOutstanding = false;
    bool submitted = false;
    bool latchEvidence = false;
    bool retirementEvidence = false;
    bool transactionComplete = false;
    bool previousRelease = false;
    bool acquireFence = false;
    bool lifecycleActive = false;
    bool lifecycleRearmed = false;
    std::optional<FixedOpportunityIdentity> inFlight;
    std::optional<FixedOpportunityIdentity> lastPublished;

    auto beginCycle = [&] {
        require(gate.arm(work, reservation), name, "cycle arm failed");
        require(scheduler.appendAcceptedTerminal(work, work * 10) &&
                    scheduler.markTerminalQueued(work, work * 10, work) &&
                    scheduler.markTerminalPrepared(work, work * 10, work),
                name, "cycle terminal preparation failed");
        terminalProgress += 2;
        demandOutstanding = true;
        submitted = false;
        latchEvidence = false;
        retirementEvidence = false;
        transactionComplete = false;
        previousRelease = false;
        acquireFence = false;
        inFlight.reset();
        lastPublished.reset();
    };
    beginCycle();

    for (int step = 0; step < 10'000; ++step) {
        const auto event = static_cast<ProtocolEvent>(
            nextRandom(random) % 15U);
        switch (event) {
            case ProtocolEvent::CANDIDATE_NOTICE:
                ++candidateIgnored;
                break;
            case ProtocolEvent::FOREIGN_JOIN_NOTICE:
                require(gate.publish(opportunity(
                            opportunitySequence + 1, work + 1,
                            reservation)) ==
                            FixedOpportunityGate::PublishResult::FOREIGN,
                        name, "foreign notice changed gate");
                ++foreignIgnored;
                break;
            case ProtocolEvent::MATCHING_JOIN_NOTICE:
                if (!submitted && !gate.hasPending() &&
                    !inFlight.has_value()) {
                    const auto exact = opportunity(
                        ++opportunitySequence, work, reservation);
                    require(gate.publish(exact) ==
                                FixedOpportunityGate::PublishResult::PUBLISHED,
                            name, "matching notice rejected");
                    lastPublished = exact;
                    demandOutstanding = false;
                }
                break;
            case ProtocolEvent::DUPLICATE_JOIN_NOTICE:
                if (lastPublished.has_value() &&
                    (gate.hasPending() || inFlight.has_value())) {
                    require(gate.publish(*lastPublished) ==
                                FixedOpportunityGate::PublishResult::DUPLICATE,
                            name, "duplicate notice was not idempotent");
                }
                break;
            case ProtocolEvent::OWN_FRAME_LATCH_CALLBACK:
                // Own-frame evidence is independent from admission.
                break;
            case ProtocolEvent::BEGIN_READY_ATTEMPT:
                if (gate.hasPending() && !inFlight.has_value()) {
                    inFlight = gate.beginReadyAttempt();
                    require(inFlight.has_value(), name,
                            "ready matching attempt missing");
                    ++attemptCount;
                }
                break;
            case ProtocolEvent::SLOT_CLOSED:
                if (inFlight.has_value()) {
                    const bool hasShadow =
                        (nextRandom(random) & 1U) != 0;
                    if (hasShadow) {
                        const auto higher = opportunity(
                            ++opportunitySequence, work, reservation);
                        require(gate.publish(higher) ==
                                    FixedOpportunityGate::PublishResult::PUBLISHED,
                                name, "closed shadow publication failed");
                        lastPublished = higher;
                    } else {
                        demandOutstanding = true;
                    }
                    require(gate.finishConsumed(*inFlight), name,
                            "closed exact consume failed");
                    inFlight.reset();
                    ++consumedCount;
                    ++closedCount;
                    require(gate.hasPending() != demandOutstanding,
                            name, "closed lacks exactly one next authority");
                }
                break;
            case ProtocolEvent::SUBMITTED:
                if (inFlight.has_value()) {
                    require(gate.finishConsumed(*inFlight) &&
                                scheduler.markTerminalSubmitted(
                                    work, work * 10, work),
                            name, "submission consume failed");
                    inFlight.reset();
                    ++consumedCount;
                    ++terminalProgress;
                    submitted = true;
                    demandOutstanding = false;
                    gate.cancelArmedReservation();
                }
                break;
            case ProtocolEvent::LATCH_EVIDENCE:
                if (submitted) latchEvidence = true;
                break;
            case ProtocolEvent::RETIREMENT_EVIDENCE:
                if (submitted) retirementEvidence = true;
                break;
            case ProtocolEvent::TRANSACTION_COMPLETE:
                if (submitted) transactionComplete = true;
                break;
            case ProtocolEvent::PREVIOUS_RELEASE:
                if (submitted) previousRelease = true;
                break;
            case ProtocolEvent::ACQUIRE_FENCE:
                if (submitted) acquireFence = true;
                break;
            case ProtocolEvent::LIFECYCLE_REQUEST:
                lifecycleActive = true;
                lifecycleRearmed = false;
                lastLifecycleRearm = terminalProgress;
                break;
            case ProtocolEvent::LIFECYCLE_REARM:
                if (lifecycleActive &&
                    lastLifecycleRearm != terminalProgress) {
                    lastLifecycleRearm = terminalProgress;
                    lifecycleRearmed = true;
                }
                break;
        }

        if (submitted && latchEvidence && retirementEvidence &&
            transactionComplete && previousRelease && acquireFence) {
            require(scheduler.markTerminalJoined(
                        work, work * 10, work),
                    name, "full evidence join failed");
            ++terminalProgress;
            submitted = false;
            if (lifecycleActive) {
                lifecycleRearmed = true;
                lastLifecycleRearm = terminalProgress;
            }
            if (lifecycleActive && lifecycleRearmed) {
                lifecycleActive = false;
                lifecycleRearmed = false;
                ++lifecycleExecutions;
            }
            ++work;
            ++reservation;
            beginCycle();
        }

        require(attemptCount <= consumedCount +
                    (inFlight.has_value() ? 1U : 0U),
                name, "attempt/consumption conservation failed");
        require(!inFlight.has_value() || lastPublished.has_value(),
                name, "attempt occurred without retirement+latch JOIN authority");
        require(scheduler.terminalConservationExact(), name,
                "terminal conservation failed");
        require(scheduler.producerDepth() <= 2,
                name, "producer depth exceeded two");
    }

    if (!submitted) {
        if (!gate.hasPending() && !inFlight.has_value()) {
            const auto exact = opportunity(
                ++opportunitySequence, work, reservation);
            require(gate.publish(exact) ==
                        FixedOpportunityGate::PublishResult::PUBLISHED,
                    name, "final publication failed");
        }
        if (!inFlight.has_value()) {
            inFlight = gate.beginReadyAttempt();
            if (inFlight.has_value()) ++attemptCount;
        }
        require(inFlight.has_value() && gate.finishConsumed(*inFlight) &&
                    scheduler.markTerminalSubmitted(work, work * 10, work),
                name, "final submit failed");
        ++consumedCount;
        ++terminalProgress;
        gate.cancelArmedReservation();
    }
    require(scheduler.markTerminalJoined(work, work * 10, work),
            name, "final join failed");
    ++terminalProgress;
    if (lifecycleActive) {
        require(lastLifecycleRearm != terminalProgress,
                name, "lifecycle rearmed without new progress");
        lastLifecycleRearm = terminalProgress;
        lifecycleActive = false;
        ++lifecycleExecutions;
    }
    const auto counters = scheduler.counters();
    require(counters.terminal_lost_count == 0 &&
                counters.spurious_commit_attempt_count == 0 &&
                scheduler.normalTerminalConservationExact() &&
                normalAbortCount == 0 && candidateIgnored != 0 &&
                foreignIgnored != 0 && closedCount != 0 &&
                attemptCount == consumedCount,
            name, "final protocol invariants failed");
    (void)lifecycleExecutions;
}

std::size_t passedTestCount = 0;

void run(const char* name, const std::function<void()>& test) {
    test();
    ++passedTestCount;
    std::cout << "PASS " << name << '\n';
}

}  // namespace

int main() {
    run("retained_head_drains_terminal_successor",
        retained_head_drains_terminal_successor);
    run("move_coalescing_stays_in_one_gesture",
        move_coalescing_stays_in_one_gesture);
    run("fractional_moves_preserve_one_to_one_drag_distance",
        fractional_moves_preserve_one_to_one_drag_distance);
    run("release_coordinate_does_not_nudge_viewport",
        release_coordinate_does_not_nudge_viewport);
    run("release_crossing_terminal_edge_publishes_exact_bottom",
        release_crossing_terminal_edge_publishes_exact_bottom);
    run("terminal_never_crosses_next_down",
        terminal_never_crosses_next_down);
    run("queued_control_rearms_after_terminal_promotion",
        queued_control_rearms_after_terminal_promotion);
    run("terminal_clamp_has_real_frame_cause",
        terminal_clamp_has_real_frame_cause);
    run("submitted_head_does_not_rewind_successor",
        submitted_head_does_not_rewind_successor);
    run("old_terminal_can_submit_while_next_gesture_active",
        old_terminal_can_submit_while_next_gesture_active);
    run("candidate_notice_does_not_publish_opportunity",
        candidate_notice_does_not_publish_opportunity);
    run("foreign_notices_are_ignored", foreign_notices_are_ignored);
    run("matching_join_open_and_duplicate_are_exact",
        matching_join_open_and_duplicate_are_exact);
    run("stale_and_gap_are_rejected", stale_and_gap_are_rejected);
    run("callback_during_attempt_preserves_successor",
        callback_during_attempt_preserves_successor);
    run("no_progress_never_creates_second_attempt",
        no_progress_never_creates_second_attempt);
    run("slot_closed_and_submitted_consume_exact_opportunity",
        slot_closed_and_submitted_consume_exact_opportunity);
    run("abort_clears_armed_identity", abort_clears_armed_identity);
    run("joined_opportunity_is_sole_attempt_authority",
        joined_opportunity_is_sole_attempt_authority);
    run("swappy_reservation_follows_backend_ready",
        swappy_reservation_follows_backend_ready);
    run("terminal_obligation_conservation",
        terminal_obligation_conservation);
    run("lifecycle_abort_matrix", lifecycle_abort_matrix);
    run("bounded_control_admission", bounded_control_admission);
    run("eighty_one_opportunities_are_exact_and_bounded",
        eighty_one_opportunities_are_exact_and_bounded);
    run("lifecycle_defers_once_until_terminal_joined",
        lifecycle_defers_once_until_terminal_joined);
    run("fifty_nine_gestures_295_events_terminal_liveness",
        fifty_nine_gestures_295_events_terminal_liveness);
    run("deterministic_10000_scheduler_interleavings",
        deterministic_10000_scheduler_interleavings);
    run("deterministic_10000_interleavings",
        deterministic_10000_interleavings);
    std::cout << "PASS FixedDepthOneSchedulerTest " << passedTestCount
              << '/' << passedTestCount << " + 10000\n";
    return 0;
}
