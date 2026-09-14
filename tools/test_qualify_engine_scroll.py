"""Synthetic fixtures for tools/qualify_engine_scroll.py. Stdlib unittest only.

Fixtures mirror the schema-v3 harness contract:
  * raw frames become moving evidence only inside a stage's measured local window,
    with unique tokens, unique/monotonic DISPLAY_PRESENT timestamps, changing
    motion keys and signed motion coordinates,
  * LOADING attempts exist before the first-viewport milestone and never after it,
  * NEXT_BOUNDARY tokens are fresh for the stage that observed them,
  * missing fences are UNVERIFIED, never PASS.
"""

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import qualify_engine_scroll as checker
import qualify_engine_scroll_cold as cold

TARGETED = checker.TARGETED_STAGES
FRAME_PERIOD = 16_666_667
LOADING_MILESTONE_NANOS = 150_000_000
SCOPE_SOURCE = "ntk"
SCOPE_SERIES = "/webtoon/843194"
SCOPE_EPISODE = "/webtoon/843194/nv-843194-38"
UNRELATED_PAGE = "a" * 64 + "-" + "b" * 64 + "-" + "c" * 64 + ".page"


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, indent=2), encoding="utf-8")


def sawtooth_coordinate(index: int) -> int:
    return index % 100


def sawtooth_direction_changes(rows: int) -> int:
    """Direction flips of coordinate = index % 100 observed over `rows` samples."""
    drops = (rows - 1) // 100
    rises = drops - 1 if (rows - 1) % 100 == 0 else drops
    return drops + max(rises, 0)


def stage_frames(stage: str, moving: int, start: int) -> list[dict]:
    rows = []
    for index in range(moving + 1):
        submitted = start + index * FRAME_PERIOD
        rows.append({
            "ordinal": index + 1,
            "stage": stage,
            "rendererId": 7,
            "sessionId": 1,
            "surfaceEpoch": 1,
            "token": f"tok-{stage}-{index}",
            "submittedAtNanos": submitted,
            "timestampNanos": submitted + 5_000_000,
            "timestampKind": "DISPLAY_PRESENT",
            "swapSucceeded": True,
            "visiblePlacementCount": 2,
            "completeViewportCoverage": True,
            "motionKey": f"page-a:0:{stage}:{index}",
            "motionCoordinate": sawtooth_coordinate(index),
        })
    return rows


def inventory_record(names: list[str], with_hashes: bool = True) -> dict:
    entries = []
    for index, name in enumerate(names):
        entries.append({
            "name": name,
            "sizeBytes": 1000 + index,
            "sha256": f"{index + 1:064x}" if with_hashes else None,
        })
    canonical = json.dumps(sorted(entries, key=lambda item: item["name"]),
                           sort_keys=True, separators=(",", ":"))
    return {
        "available": True, "pageCacheNames": names, "entries": entries, "count": len(names),
        "contentHashesAvailable": bool(entries) and all(entry["sha256"] for entry in entries),
        "inventorySha256": hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
        "deviceCacheGloballyCleared": False,
    }


def build_run(root: Path, *, lost_inputs: int = 0, short_stage: bool = False,
              boundary_observed: bool = True, raw_claim: bool = False,
              duplicate_timestamp: bool = False, close_mismatch: bool = False,
              stale_boundary: bool = False, pending_fence: bool = False,
              cancelled_close_frame: bool = False,
              loading_replay: bool = False, missing_attempts: bool = False,
              cache_populated: bool = False, cache_clear_claim: bool = False,
              cache_no_hashes: bool = False, cache_tampered_digest: bool = False,
              scoped_cold: bool = False, scope_tampered: bool = False,
              scoped_missing_restoration: bool = False, scope_wrong_episode: bool = False,
              prepared_sha: str = "prepared-db-sha-1") -> Path:
    run = root / "run"
    capture = run / "trace" / "engine-scroll-qualification-1"
    capture.mkdir(parents=True)
    states = {"READY_ROUND_TRIP": 1000, "FAST_REVERSE": 1000, "ENDPOINT_END": 1000,
              "NEXT_BOUNDARY": 1000, "ENDPOINT_START": 1000}
    if short_stage:
        states["READY_ROUND_TRIP"] = 500
    frames: list[dict] = []
    stages = [
        {"stage": "LOADING", "startedAtNanos": 0, "endedAtNanos": LOADING_MILESTONE_NANOS,
         "gestures": 0, "setupGestures": 0, "movingDisplayFrames": 0,
         "completeCoverageMovingDisplayFrames": 0, "setupMovingDisplayFrames": 0,
         "measuredFromNanos": 0, "measuredMovingDisplayFrames": 0,
         "measuredDirectionChanges": 0, "displayPresentFrames": 0,
         "readableDisplayPresentFrames": 0, "firstDisplayPresentAtNanos": 0,
         "targetMovingFrames": 0, "budgetExhausted": False},
        {"stage": "STREAMING", "startedAtNanos": LOADING_MILESTONE_NANOS,
         "endedAtNanos": 400_000_000, "gestures": 5, "setupGestures": 5,
         "movingDisplayFrames": 10, "completeCoverageMovingDisplayFrames": 10,
         "setupMovingDisplayFrames": 10, "measuredFromNanos": 0,
         "measuredMovingDisplayFrames": 0, "measuredDirectionChanges": 0,
         "displayPresentFrames": 11, "readableDisplayPresentFrames": 11,
         "firstDisplayPresentAtNanos": 0, "targetMovingFrames": 0,
         "budgetExhausted": False},
    ]
    boundary_start = 0
    start = 1_000_000_000
    for name in TARGETED:
        moving = states[name]
        batch = stage_frames(name, moving, start)
        frames += batch
        rows = moving + 1
        if name == "NEXT_BOUNDARY":
            boundary_start = start
        stages.append({
            "stage": name, "startedAtNanos": start,
            "endedAtNanos": start + (moving + 1) * FRAME_PERIOD,
            "gestures": 20, "setupGestures": 4,
            "movingDisplayFrames": rows,
            "completeCoverageMovingDisplayFrames": rows,
            "setupMovingDisplayFrames": 3,
            "measuredFromNanos": start,
            "measuredMovingDisplayFrames": rows,
            "measuredDirectionChanges": sawtooth_direction_changes(rows),
            "displayPresentFrames": rows, "readableDisplayPresentFrames": rows,
            "firstDisplayPresentAtNanos": start, "targetMovingFrames": 1000,
            "budgetExhausted": False,
        })
        start += (moving + 2) * FRAME_PERIOD
    if duplicate_timestamp:
        duplicate = dict(frames[0])
        duplicate["ordinal"] = len(frames) + 1
        frames.append(duplicate)
    if pending_fence:
        pending = dict(frames[0])
        pending["ordinal"] = len(frames) + 1
        pending["stage"] = "READY_ROUND_TRIP"
        pending["token"] = 0x5EED0001
        pending["timestampKind"] = "COMPOSITION_LATCH"
        pending["motionKey"] = "page-a:0:pending-fence"
        frames.append(pending)
    if cancelled_close_frame:
        cancelled = dict(frames[0])
        cancelled["ordinal"] = len(frames) + 1
        cancelled["stage"] = "ENDPOINT_START"
        cancelled["token"] = 0x0CA4CE1
        cancelled["timestampKind"] = "CANCELLED"
        cancelled["timestampNanos"] = 0
        cancelled["motionKey"] = "page-a:0:cancelled-close"
        frames.append(cancelled)
    (capture / "frames.jsonl").write_text(
        "".join(json.dumps(row) + "\n" for row in frames), encoding="utf-8")
    if stale_boundary:
        boundary_nanos = max(boundary_start - 100, 1)
    else:
        boundary_nanos = boundary_start + 10
    write_json(capture / "stage-evidence.json", {
        "schemaVersion": 3,
        "movingEvidenceSource": "engineFramesSince DISPLAY_PRESENT frames",
        "stages": stages,
        "integrity": {
            "frameLostCount": 0, "frameObservationCount": len(frames),
            "inputLostCount": lost_inputs, "inputObservationCount": 1,
            "motionObservationCount": 1, "motionHistoryOverwritten": False,
            "gestureWindowsTruncated": False, "windowFramesDropped": False,
            "frameInvalidDisplayPresentTimestamps": 0, "frameDuplicateNativeTimestamps": 0,
            "frameNonMonotonicNativeTimestamps": 0,
            "legacyPresentationDropped": False, "legacyPresentationRows": 0,
        },
        "boundary": {
            "firstNextSourceToken": 111 if boundary_observed else None,
            "firstNextAnchorToken": 112 if boundary_observed else None,
            "currentAndNextShareViewport": boundary_observed,
            "completeNextFrameObserved": boundary_observed,
            "firstNextSourceAtNanos": boundary_nanos if boundary_observed else None,
            "firstNextAnchorAtNanos": boundary_nanos + 1 if boundary_observed else None,
            "completeNextFrameAtNanos": boundary_nanos + 2 if boundary_observed else None,
            "shareViewportAtNanos": boundary_nanos + 3 if boundary_observed else None,
            "boundaryFrames": 3,
        },
        "endpoints": {"firstPageStartToken": 5, "lastPageEndToken": 9,
                      "traversedDocumentEndpoints": True,
                      "startTokenAtNanos": boundary_start, "endTokenAtNanos": boundary_start},
        "loading": {"attemptCount": 0 if missing_attempts else (4 if not loading_replay else 5),
                    "firstCompleteViewportSubmittedAtNanos": LOADING_MILESTONE_NANOS,
                    "firstCompleteDisplayPresentAtNanos": LOADING_MILESTONE_NANOS + 5_000_000},
        "rawInputBinding": {"available": False},
        "physicalPresentationVerified": False,
        "corpusCredit": 0,
    })
    attempts = [
        {"ordinal": 1, "atNanos": 10_000_000, "surfaceReady": False},
        {"ordinal": 2, "atNanos": 40_000_000, "surfaceReady": True},
        {"ordinal": 3, "atNanos": 80_000_000, "surfaceReady": True},
        {"ordinal": 4, "atNanos": 120_000_000, "surfaceReady": True},
    ]
    if loading_replay:
        attempts.append({"ordinal": 5, "atNanos": LOADING_MILESTONE_NANOS + 1_000_000,
                         "surfaceReady": True})
    if not missing_attempts:
        (capture / "loading-attempts.jsonl").write_text(
            "".join(json.dumps(row) + "\n" for row in attempts), encoding="utf-8")
    write_json(capture / "renderer-close.json", {
        "submittedFrameCount": len(frames) - (1 if close_mismatch else 0),
        "deliveredObservationCount": len(frames) - (1 if close_mismatch else 0),
    })
    (capture / "inputs.jsonl").write_text(json.dumps({
        "ordinal": 1, "inputRevision": 1, "rawBindingAvailable": False, "rawEventTimeNanos": None,
    }) + "\n", encoding="utf-8")
    write_json(capture / "input-close.json", {"observationCount": 1, "receivedInputCount": 1})
    (capture / "motion.jsonl").write_text(json.dumps({
        "ordinal": 1, "sequence": 1, "frameTimeNanos": 1, "appliedAtNanos": 1,
    }) + "\n", encoding="utf-8")
    (capture / "motion-windows.jsonl").write_text(json.dumps({
        "ordinal": 1, "startNanos": 1, "endNanos": 2,
    }) + "\n", encoding="utf-8")
    write_json(capture / "motion-close.json", {
        "observationCount": 1, "windowCount": 1, "historyOverwritten": False,
    })
    write_json(capture / "summary.json", {
        "rawInputBindingAvailable": raw_claim, "physicalPresentationVerified": False,
    })
    page_names = ["page-a", "page-b", "page-c"] if cache_populated else []
    before = inventory_record(page_names, with_hashes=not cache_no_hashes)
    after = inventory_record(page_names, with_hashes=not cache_no_hashes)
    if cache_tampered_digest:
        before["inventorySha256"] = "0" * 64
    write_json(run / "cache-inventory-before.json", before)
    write_json(run / "cache-inventory-after.json", after)
    write_json(run / "cache-inventory.json", {
        "available": True, "count": len(page_names), "pageCacheNames": page_names,
        "deviceCacheGloballyCleared": False,
    })
    scope = None
    if scoped_cold:
        scope = {
            "sourceId": SCOPE_SOURCE, "seriesKey": SCOPE_SERIES,
            "requestedEpisodeKey": SCOPE_EPISODE, "scopeEpisodes": [SCOPE_EPISODE],
            "pageCacheKeys": [], "planFiles": [], "stagingFiles": [], "journalFiles": [],
        }
        scope["scopeSha256"] = cold.scope_digest(scope)
        if scope_tampered:
            scope["scopeSha256"] = "0" * 64
    case_episode = "ep-other" if scope_wrong_episode else (SCOPE_EPISODE if scoped_cold else "ep-1")
    write_json(run.parent / "installed.json", {
        "caseOrdinal": 1,
        "case": {"sourceId": SCOPE_SOURCE if scoped_cold else "ntk",
                 "seriesKey": SCOPE_SERIES if scoped_cold else "site:1",
                 "episodeKey": case_episode, "kind": "WEBTOON"},
    })
    write_json(run / "cache-state.json", {
        "cachePagesBefore": len(page_names), "cachePagesAfter": len(page_names),
        "stateBefore": "empty" if not page_names else "populated",
        "stateAfter": "empty" if not page_names else "populated",
        "forceStopOnly": True, "deviceCacheCleared": cache_clear_claim,
        "naturalColdClaimable": not page_names and not cache_clear_claim,
        **({} if scope is None else {
            "scope": scope,
            "scopedCachePagesBefore": 0, "scopedCachePagesAfter": 4,
            "scopedEntriesAbsentBefore": True,
            "unrelatedEntriesUnchanged": True,
            "scopeRestorationVerified": not scoped_missing_restoration,
            "entryState": {"databaseRestored": True, "prepareP0Applied": True,
                           "preparedDatabaseSha256": prepared_sha, "bookmarksPreserved": True},
        }),
    })
    if scope is not None:
        write_json(run / "scope-inventory-before.json", {
            "scopeSha256": scope["scopeSha256"], "entries": [], "absentPageCacheKeys": [],
            "absentPlanFiles": [], "absentStagingFiles": [],
            "scopedCacheEntriesPresent": 0, "scopedEntriesAbsent": True, "inventorySha256": "e" * 64,
        })
        roots = {root: {"entries": ([{"name": UNRELATED_PAGE, "sha256": "d" * 64, "sizeBytes": 11}]
                                    if root == cold.PAGES_ROOT else [])}
                 for root in cold.ROOT_ORDER}
        inventory = {"roots": roots, "inventorySha256": cold.roots_digest(roots)}
        write_json(run.parent / "cache-baseline.json", inventory)
        write_json(run.parent / "cache-final-inventory.json", inventory)
        if not scoped_missing_restoration:
            write_json(run.parent / "scope-restoration.json", {
                "scopeRestorationVerified": True, "globalBaselineExact": True,
                "scopeSha256": scope["scopeSha256"], "scopeEntries": 0,
            })
    write_json(run / "analysis.json", {"runs": [{"viewer": {"criteria": [
        {"criterion": "display_jank_missed_refresh_ratio_lt_1pct", "state": "PASS"},
        {"criterion": "display_no_100ms_moving_stall", "state": "PASS"},
        {"criterion": "physical_touch_to_display_p95_le_50_max_lt_100", "state": "UNVERIFIED"},
        {"criterion": "three_consecutive_cold_runs", "state": "UNVERIFIED"},
    ]}}]})
    return run


class QualifyEngineScrollTest(unittest.TestCase):
    def states_for(self, run: Path, require_boundary: bool = True) -> dict:
        result = checker.check_run(run, require_boundary=require_boundary)
        return {item["criterion"]: item["state"] for item in result["criteria"]}

    def test_good_run_has_no_failures(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            result = checker.check_run(build_run(Path(tmp)), require_boundary=True)
            states = {item["criterion"]: item["state"] for item in result["criteria"]}
            self.assertEqual(states["moving_stage_coverage"], checker.PASS)
            self.assertEqual(states["ring_integrity"], checker.PASS)
            self.assertEqual(states["next_boundary_observed"], checker.PASS)
            self.assertEqual(states["prefirst_viewport_input_attempts"], checker.PASS)
            self.assertEqual(states["display_fence_coverage"], checker.PASS)
            self.assertEqual(states["raw_input_binding_honest"], checker.PASS)
            self.assertEqual(states["cache_inventory_recorded"], checker.PASS)
            self.assertEqual(states["natural_cold_state"], checker.PASS)
            self.assertEqual(states["cache_state_provenance"], checker.PASS)
            self.assertEqual(result["state"], checker.UNVERIFIED)

    def test_lost_inputs_fail_integrity(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            result = checker.check_run(build_run(Path(tmp), lost_inputs=1), require_boundary=True)
            states = {item["criterion"]: item["state"] for item in result["criteria"]}
            self.assertEqual(states["ring_integrity"], checker.FAIL)
            self.assertEqual(result["state"], checker.FAIL)

    def test_short_steady_stage_fails_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            result = checker.check_run(build_run(Path(tmp), short_stage=True), require_boundary=True)
            states = {item["criterion"]: item["state"] for item in result["criteria"]}
            self.assertEqual(states["moving_stage_coverage"], checker.FAIL)

    def test_duplicate_native_timestamp_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            result = checker.check_run(build_run(Path(tmp), duplicate_timestamp=True),
                                       require_boundary=True)
            states = {item["criterion"]: item["state"] for item in result["criteria"]}
            self.assertEqual(states["ring_integrity"], checker.FAIL)

    def test_close_proof_mismatch_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            result = checker.check_run(build_run(Path(tmp), close_mismatch=True),
                                       require_boundary=True)
            states = {item["criterion"]: item["state"] for item in result["criteria"]}
            self.assertEqual(states["ring_integrity"], checker.FAIL)

    def test_raw_binding_claim_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            result = checker.check_run(build_run(Path(tmp), raw_claim=True), require_boundary=True)
            states = {item["criterion"]: item["state"] for item in result["criteria"]}
            self.assertEqual(states["raw_input_binding_honest"], checker.FAIL)

    def test_missing_boundary_is_unverified_when_allowed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            run = build_run(Path(tmp), boundary_observed=False)
            strict = self.states_for(run, require_boundary=True)
            relaxed = self.states_for(run, require_boundary=False)
            self.assertEqual(strict["next_boundary_observed"], checker.FAIL)
            self.assertEqual(relaxed["next_boundary_observed"], checker.UNVERIFIED)

    def test_historical_boundary_tokens_fail(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            states = self.states_for(build_run(Path(tmp), stale_boundary=True))
            self.assertEqual(states["next_boundary_observed"], checker.FAIL)

    def test_pending_fence_is_unverified_not_pass(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            result = checker.check_run(build_run(Path(tmp), pending_fence=True),
                                       require_boundary=True)
            states = {item["criterion"]: item["state"] for item in result["criteria"]}
            self.assertEqual(states["display_fence_coverage"], checker.UNVERIFIED)
            self.assertEqual(result["state"], checker.UNVERIFIED)

    def write_effective_sidecar(self, run: Path, *, data_loss: bool = False,
                                withhold: set | None = None, duplicate: bool = False) -> None:
        capture = run / "trace" / "engine-scroll-qualification-1"
        frames = [json.loads(line) for line
                  in (capture / "frames.jsonl").read_text(encoding="utf-8").splitlines()
                  if line.strip()]
        presentations = []
        for frame in frames:
            token = frame.get("token")
            if token is None or frame.get("swapSucceeded") is False:
                continue
            if withhold and token in withhold:
                presentations.append({"frameToken": token, "status": "WITHHELD_TRACE_LOSS",
                                      "effectiveKind": None, "effectiveTimestampNanos": None})
                continue
            value = frame.get("timestampNanos") or 0
            if frame.get("timestampKind") == "DISPLAY_PRESENT" and value > 0:
                presentations.append({"frameToken": token, "status": "RAW_DISPLAY_PRESENT",
                                      "effectiveKind": "DISPLAY_PRESENT",
                                      "effectiveTimestampNanos": value})
            else:
                presentations.append({"frameToken": token, "status": "UNIQUE_LATE_SIGNAL",
                                      "effectiveKind": "DISPLAY_PRESENT",
                                      "effectiveTimestampNanos": (frame.get("submittedAtNanos") or 0)
                                      + 1_000_000})
        if duplicate and len(presentations) > 1:
            presentations[1]["effectiveTimestampNanos"] = presentations[0]["effectiveTimestampNanos"]
        write_json(run / "effective-presentations.json", {
            "schemaVersion": 3, "traceDataLoss": data_loss, "summary": None,
            "presentations": presentations,
        })

    def test_latch_frame_passes_with_validated_sidecar(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            run = build_run(Path(tmp), pending_fence=True)
            self.write_effective_sidecar(run)
            states = self.states_for(run)
            self.assertEqual(states["display_fence_coverage"], checker.PASS)

    def test_latch_frame_unverified_when_sidecar_withholds(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            run = build_run(Path(tmp), pending_fence=True)
            self.write_effective_sidecar(run, withhold={0x5EED0001})
            states = self.states_for(run)
            self.assertEqual(states["display_fence_coverage"], checker.UNVERIFIED)

    def test_latch_frame_unverified_when_trace_reports_data_loss(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            run = build_run(Path(tmp), pending_fence=True)
            self.write_effective_sidecar(run, data_loss=True)
            states = self.states_for(run)
            self.assertEqual(states["display_fence_coverage"], checker.UNVERIFIED)

    def test_duplicate_effective_timestamp_blocks_sidecar_pass(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            run = build_run(Path(tmp), pending_fence=True)
            self.write_effective_sidecar(run, duplicate=True)
            states = self.states_for(run)
            self.assertEqual(states["display_fence_coverage"], checker.UNVERIFIED)

    def test_cancelled_close_frame_does_not_block_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            states = self.states_for(build_run(Path(tmp), cancelled_close_frame=True))
            self.assertEqual(states["display_fence_coverage"], checker.PASS)
            self.assertEqual(states["ring_integrity"], checker.PASS)

    def test_loading_attempt_after_milestone_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            states = self.states_for(build_run(Path(tmp), loading_replay=True))
            self.assertEqual(states["prefirst_viewport_input_attempts"], checker.FAIL)

    def test_missing_loading_attempts_unverified(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            states = self.states_for(build_run(Path(tmp), missing_attempts=True))
            self.assertEqual(states["prefirst_viewport_input_attempts"], checker.UNVERIFIED)

    def test_missing_measured_window_unverified(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            run = build_run(Path(tmp))
            evidence_path = run / "trace" / "engine-scroll-qualification-1" / "stage-evidence.json"
            evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
            for stage in evidence["stages"]:
                if stage["stage"] == "ENDPOINT_END":
                    stage["measuredFromNanos"] = 0
            write_json(evidence_path, evidence)
            states = self.states_for(run)
            self.assertEqual(states["moving_stage_coverage"], checker.UNVERIFIED)

    def test_live_measured_claim_contradicting_raw_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            run = build_run(Path(tmp))
            evidence_path = run / "trace" / "engine-scroll-qualification-1" / "stage-evidence.json"
            evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
            for stage in evidence["stages"]:
                if stage["stage"] == "FAST_REVERSE":
                    stage["measuredMovingDisplayFrames"] = 9_000
            write_json(evidence_path, evidence)
            states = self.states_for(run)
            self.assertEqual(states["moving_stage_coverage"], checker.FAIL)


    def test_populated_cache_marks_cold_state_unverified(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            states = self.states_for(build_run(Path(tmp), cache_populated=True))
            self.assertEqual(states["natural_cold_state"], checker.UNVERIFIED)

    def test_cache_clear_claim_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            states = self.states_for(build_run(Path(tmp), cache_clear_claim=True))
            self.assertEqual(states["natural_cold_state"], checker.FAIL)

    def test_missing_cache_hashes_unverified(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            states = self.states_for(build_run(Path(tmp), cache_populated=True,
                                               cache_no_hashes=True))
            self.assertEqual(states["cache_state_provenance"], checker.UNVERIFIED)

    def test_tampered_cache_digest_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            states = self.states_for(build_run(Path(tmp), cache_tampered_digest=True))
            self.assertEqual(states["cache_state_provenance"], checker.FAIL)


    def test_scoped_cold_pass_with_populated_global_cache(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            states = self.states_for(build_run(Path(tmp), scoped_cold=True, cache_populated=True))
            self.assertEqual(states["natural_cold_state"], checker.PASS)

    def test_scoped_scope_hash_contradiction_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            states = self.states_for(build_run(Path(tmp), scoped_cold=True, cache_populated=True,
                                               scope_tampered=True))
            self.assertEqual(states["natural_cold_state"], checker.FAIL)

    def test_scoped_missing_restoration_unverified(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            states = self.states_for(build_run(Path(tmp), scoped_cold=True, cache_populated=True,
                                               scoped_missing_restoration=True))
            self.assertEqual(states["natural_cold_state"], checker.UNVERIFIED)

    def test_scoped_wrong_episode_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            states = self.states_for(build_run(Path(tmp), scoped_cold=True, cache_populated=True,
                                               scope_wrong_episode=True))
            self.assertEqual(states["natural_cold_state"], checker.FAIL)

    def test_per_run_entry_position_matches_or_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            first = build_run(root / "a", scoped_cold=True, cache_populated=True, prepared_sha="p1")
            second = build_run(root / "b", scoped_cold=True, cache_populated=True, prepared_sha="p1")
            output = root / "aggregate.json"
            checker.main(["--run", str(first), "--run", str(second), "--output", str(output)])
            report = json.loads(output.read_text(encoding="utf-8"))
            entry = next(item for item in report["aggregateCriteria"]
                         if item["criterion"] == "per_run_entry_position")
            self.assertEqual(entry["state"], checker.PASS)
            third = build_run(root / "c", scoped_cold=True, cache_populated=True, prepared_sha="p2")
            output2 = root / "aggregate2.json"
            checker.main(["--run", str(first), "--run", str(third), "--output", str(output2)])
            report2 = json.loads(output2.read_text(encoding="utf-8"))
            entry2 = next(item for item in report2["aggregateCriteria"]
                          if item["criterion"] == "per_run_entry_position")
            self.assertEqual(entry2["state"], checker.FAIL)


if __name__ == "__main__":
    unittest.main()
