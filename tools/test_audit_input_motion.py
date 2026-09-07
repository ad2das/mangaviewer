import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("audit_input_motion.py")
SPEC = importlib.util.spec_from_file_location("audit_input_motion", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class InputMotionAuditTests(unittest.TestCase):
    def test_event_time_interval_has_one_ms_bounds(self):
        value = MODULE.interval(12_000_000, MODULE.NS_PER_MS)
        self.assertEqual(value["nominalNanos"], 12_000_000)
        self.assertEqual(value["lowerNanos"], 11_000_000)
        self.assertEqual(value["upperNanos"], 13_000_000)

    def test_application_marker_parses_exact_state_tuple(self):
        markers = MODULE.parse_motion_applied_markers({
            "markers": [{
                "id": 55,
                "ts": 10_000,
                "dur": 20,
                "name": "viewer_motion_applied:7:9000:44:3:2",
            }]
        })
        marker = markers[7][0]
        self.assertEqual(marker["appliedAtNanos"], 9_000)
        self.assertEqual(marker["userInputRevision"], 44)
        self.assertEqual(marker["generation"], 3)
        self.assertEqual(marker["rendererEpoch"], 2)

    def test_multiple_eligible_application_markers_are_ambiguous(self):
        applied = {"motionSequence": 7, "appliedAtNanos": 1_000_000}
        candidates = [
            {"id": 1, "ts": 10, "motionSequence": 7, "appliedAtNanos": 999_500},
            {"id": 2, "ts": 20, "motionSequence": 7, "appliedAtNanos": 1_000_400},
        ]
        self.assertIsNone(MODULE.nearest_application_marker(applied, candidates))

    def test_equal_distance_application_markers_are_ambiguous(self):
        applied = {"motionSequence": 7, "appliedAtNanos": 1_000_000}
        candidates = [
            {"id": 1, "ts": 10, "motionSequence": 7, "appliedAtNanos": 999_500},
            {"id": 2, "ts": 20, "motionSequence": 7, "appliedAtNanos": 1_000_500},
        ]
        self.assertIsNone(MODULE.nearest_application_marker(applied, candidates))

    def test_wrong_sequence_application_marker_is_rejected(self):
        applied = {"motionSequence": 7, "appliedAtNanos": 1_000_000}
        candidates = [
            {"id": 3, "ts": 30, "motionSequence": 8, "appliedAtNanos": 999_500},
        ]
        self.assertIsNone(MODULE.nearest_application_marker(applied, candidates))

    def test_single_eligible_application_marker_wins_over_outside_candidates(self):
        applied = {"motionSequence": 7, "appliedAtNanos": 1_000_000}
        candidates = [
            {"id": 1, "ts": 10, "motionSequence": 7, "appliedAtNanos": 1_000_400},
            {"id": 2, "ts": 20, "motionSequence": 7, "appliedAtNanos": 2_100_000},
            {"id": 3, "ts": 30, "motionSequence": 8, "appliedAtNanos": 1_000_100},
        ]
        selected = MODULE.nearest_application_marker(applied, candidates)
        self.assertIsNotNone(selected)
        self.assertEqual(selected["id"], 1)

    def test_missing_application_marker_cannot_promote_submit(self):
        prepare = {
            7: [{
                "id": 11, "ts": 1_010, "dur": 20, "name": "viewer_prepare:7:1000",
                "track_id": 20, "parent_id": None, "token": 7,
                "offeredAtNanos": 1_000, "traceEndNanos": 1_030,
            }]
        }
        presentation = {
            7: {
                "token": 7,
                "scrollCause": "USER_INPUT",
                "submittedAtNanos": 1_050,
                "userInputRevision": 4,
                "generation": 1,
                "renderer": 1,
            }
        }
        result = MODULE.renderer_detail(
            1_001, prepare, {}, presentation, 100, {"available": True},
            MODULE.application_provenance_from_marker(None, "run-a"),
        )
        self.assertIsNone(result["causalPrepare"])
        self.assertEqual(result["candidateSubmitNanos"], 1_050)
        self.assertIsNone(result["firstMatchingSubmitNanos"])
        self.assertEqual(result["identityJoinConfidence"], "UNRESOLVED")

    def test_grouping_keeps_down_move_up_and_orphans(self):
        def marker(code, timestamp):
            return {
                "id": timestamp,
                "ts": timestamp,
                "dur": 10,
                "parent_id": None,
                "action": code,
            }

        groups, orphans = MODULE.group_input([
            marker("MOVE", 1),
            marker("DOWN", 2),
            marker("MOVE", 3),
            marker("UP", 4),
        ])
        self.assertEqual(len(orphans), 1)
        self.assertEqual(len(groups), 1)
        self.assertEqual([item["action"] for item in groups[0]], ["DOWN", "MOVE", "UP"])

    def test_pre_move_empty_evidence_is_not_external(self):
        value = MODULE.pre_move_evidence(100, 200, [], [])
        self.assertEqual(value["classification"], "NO_TARGET_MAIN_SCHEDULER_OR_HANDLER_OVERLAP")
        self.assertEqual(value["externalAttribution"], "NOT_PROVEN")

    def test_pre_move_handler_overlap_is_reported(self):
        support = [{
            "id": 1, "ts": 120, "dur": 30, "name": "deliverInputEvent",
            "track_id": 10, "parent_id": None,
        }]
        value = MODULE.pre_move_evidence(100, 200, support, [])
        self.assertEqual(value["classification"], "TARGET_MAIN_SCHEDULER_OR_HANDLER_OVERLAP")
        self.assertEqual(value["handlerAndSchedulerSlices"]["overlapNanos"], 30)
        self.assertEqual(value["externalAttribution"], "NOT_PROVEN")

    def test_renderer_join_requires_causal_token_for_submit(self):
        prepare = {
            7: [{
                "id": 11, "ts": 1_010, "dur": 20, "name": "viewer_prepare:7:1000",
                "track_id": 20, "parent_id": None, "token": 7,
                "offeredAtNanos": 1_000, "traceEndNanos": 1_030,
            }]
        }
        swap = {
            7: [{
                "id": 12, "ts": 1_040, "dur": 30, "name": "viewer_swap:7:7:1039",
                "track_id": 20, "parent_id": None, "token": 7,
                "bufferFrameId": 7, "nativeSampleNanos": 1_039,
                "traceEndNanos": 1_070,
            }]
        }
        presentation = {
            7: {
                "token": 7,
                "scrollCause": "USER_INPUT",
                "submittedAtNanos": 1_050,
                "userInputRevision": 4,
                "generation": 1,
                "renderer": 1,
            }
        }
        result = MODULE.renderer_detail(
            1_001, prepare, swap, presentation, 100, {"available": True},
            {
                "exact": True,
                "userInputRevision": 4,
                "session": "run-a",
                "generation": 1,
                "rendererEpoch": 1,
            },
        )
        self.assertEqual(result["identityJoinConfidence"], "HIGH")
        self.assertEqual(result["firstMatchingSubmitNanos"], 1_050)
        self.assertEqual(result["causalPrepare"]["prepare"]["id"], 11)

    def test_renderer_does_not_promote_geometry_correction(self):
        prepare = {
            7: [{
                "id": 11, "ts": 1_010, "dur": 20, "name": "viewer_prepare:7:1000",
                "track_id": 20, "parent_id": None, "token": 7,
                "offeredAtNanos": 1_000, "traceEndNanos": 1_030,
            }]
        }
        result = MODULE.renderer_detail(
            1_001, prepare, {}, {
                7: {"token": 7, "scrollCause": "GEOMETRY_CORRECTION", "submittedAtNanos": 1_050}
            }, 100, {"available": True}
        )
        self.assertIsNone(result["causalPrepare"])
        self.assertEqual(result["firstMatchingSubmitNanos"], None)
        self.assertEqual(result["identityJoinConfidence"], "UNKNOWN")

    def test_stale_scene_late_submit_is_unresolved_without_application_provenance(self):
        prepare = {
            7: [{
                "id": 11, "ts": 1_010, "dur": 20, "name": "viewer_prepare:7:1000",
                "track_id": 20, "parent_id": None, "token": 7,
                "offeredAtNanos": 1_000, "traceEndNanos": 1_030,
            }]
        }
        swap = {
            7: [{
                "id": 12, "ts": 1_040, "dur": 30, "name": "viewer_swap:7:7:1039",
                "track_id": 20, "parent_id": None, "token": 7,
                "bufferFrameId": 7, "nativeSampleNanos": 1_039,
                "traceEndNanos": 1_070,
            }]
        }
        presentation = {
            7: {
                "token": 7,
                "scrollCause": "USER_INPUT",
                "submittedAtNanos": 1_050,
                "userInputRevision": 99,
                "session": "stale-scene",
                "generation": 2,
            }
        }
        application_provenance = MODULE.application_provenance_from_marker(None, "run-a")
        result = MODULE.renderer_detail(
            1_001, prepare, swap, presentation, 100, {"available": True},
            application_provenance,
        )
        self.assertIsNone(result["causalPrepare"])
        self.assertEqual(result["candidatePrepare"]["prepare"]["id"], 11)
        self.assertEqual(result["candidateSubmitNanos"], 1_050)
        self.assertIsNone(result["firstMatchingSubmitNanos"])
        self.assertEqual(result["identityJoinConfidence"], "UNRESOLVED")

    def test_wrong_generation_candidate_is_unresolved(self):
        prepare = {
            7: [{
                "id": 11, "ts": 1_010, "dur": 20, "name": "viewer_prepare:7:1000",
                "track_id": 20, "parent_id": None, "token": 7,
                "offeredAtNanos": 1_000, "traceEndNanos": 1_030,
            }]
        }
        swap = {
            7: [{
                "id": 12, "ts": 1_040, "dur": 30, "name": "viewer_swap:7:7:1039",
                "track_id": 20, "parent_id": None, "token": 7,
                "bufferFrameId": 7, "nativeSampleNanos": 1_039,
                "traceEndNanos": 1_070,
            }]
        }
        presentation = {
            7: {
                "token": 7,
                "scrollCause": "USER_INPUT",
                "submittedAtNanos": 1_050,
                "userInputRevision": 4,
                "generation": 1,
                "renderer": 1,
            }
        }
        result = MODULE.renderer_detail(
            1_001, prepare, swap, presentation, 100, {"available": True},
            {
                "exact": True,
                "userInputRevision": 4,
                "session": "run-a",
                "generation": 2,
                "rendererEpoch": 1,
            },
        )
        self.assertEqual(
            result["candidatePrepare"]["provenanceComparison"]["mismatches"],
            ["generation"],
        )
        self.assertIsNone(result["causalPrepare"])
        self.assertIsNone(result["firstMatchingSubmitNanos"])
        self.assertEqual(result["identityJoinConfidence"], "UNRESOLVED")

    def test_first_move_boundary_control_does_not_use_down(self):
        case = {
            "rawInput": {
                "down": {"ts": 100_000_000, "traceEndNanos": 110_000_000},
            },
            "window": {"startNanos": 200_000_000},
            "handler": {"firstMoveWindowStartWithinMarker": True},
        }
        self.assertEqual(MODULE.down_boundary_hits([case], MODULE.NS_PER_MS), 0)


if __name__ == "__main__":
    unittest.main()
