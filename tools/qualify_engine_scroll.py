#!/usr/bin/env python3
"""Qualification policy checker for the staged engine scroll capture.

Moving-display evidence comes from raw `frames.jsonl` engine DISPLAY_PRESENT observations:
positive timestamp, native-timestamp uniqueness and monotonicity per renderer identity,
changed scene anchor/source position, and close-proof completeness. The legacy packed
presentation ring is an optional diagnostic and is never a gating source. The
`display_fence_coverage` criterion counts a raw latch-only frame once the extractor's
validated effective-presentation sidecar proves it with a unique single-scope late kernel
fence (`effective-presentations.json`, written by the extractor worker).

The analysis worker's `analyze-presented-motion.py` owns fence cadence, latency, and
window criteria. This checker adds the harness contract on top of that analyzer output:

  * every steady/end/boundary stage owns >= 1000 actual moving display frames per run,
    cross-checked against raw frames,
  * ring/close integrity: no lost observations, no invalid/duplicate/non-monotonic
    DISPLAY_PRESENT timestamps, close-proof counts equal exported rows,
  * true next-boundary observation with first next source+anchor tokens,
    `currentAndNextShareViewport`, and a complete next frame — or a plan-store proof that the
    captured episode is terminal (`terminal-episode-proof.json`, decoded plan with no next
    neighbour); the same proof makes that episode's NEXT_BOUNDARY coverage requirement vacuous,
  * cache inventory recorded,
  * no fabricated raw input binding: dispatch-order gestures are never receipts.

Verdicts are PASS / FAIL / UNVERIFIED. Missing evidence is UNVERIFIED; contradictions are
FAIL. The analyzer's partial-population INCOMPLETE state is an evidence gap and folds into
UNVERIFIED. `three_consecutive_cold_runs` is a run-set criterion: the analyzer reports it
UNVERIFIED per run and the aggregate verdict decides it across the consecutive scoped-cold
runs. Design-pending criteria (recorded in `designPending`) stay visible UNVERIFIED but never
withhold a run whose every gateable criterion is PASS. Physical panel presentation is never
claimed. Stdlib only; no device access.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

SCHEMA_VERSION = 3
PASS, FAIL, UNVERIFIED = "PASS", "FAIL", "UNVERIFIED"
# Analyzer partial-population state: an evidence gap, never a measured contradiction and
# never a PASS. combine() folds it into UNVERIFIED for the receipt-level verdict.
INCOMPLETE = "INCOMPLETE"
DISPLAY_PRESENT = "DISPLAY_PRESENT"
TARGETED_STAGES = ("READY_ROUND_TRIP", "FAST_REVERSE", "ENDPOINT_END", "NEXT_BOUNDARY", "ENDPOINT_START")
TARGET_MOVING_FRAMES = 1000
MIN_DIRECTIONAL_TRANSITIONS = 4
MIN_CONSECUTIVE_COLD_RUNS = 3
# Criterion owned by the run set, not by a single run: the analyzer reports it UNVERIFIED per
# run and the aggregate verdict below decides it across the consecutive scoped-cold runs.
RUN_SET_CRITERIA = ("three_consecutive_cold_runs",)
# Documented design-pending criteria: recorded UNVERIFIED on every receipt until the raw
# MotionEvent hook lands, but never the reason a run with every gateable criterion PASS is
# withheld. The pending state stays visible in the per-run criteria and in designPending.
DESIGN_PENDING_CRITERIA = ("physical_touch_to_display_p95_le_50_max_lt_100",)


def read_json(path: Path):
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8-sig"))


def read_jsonl(path: Path) -> list[dict]:
    rows: list[dict] = []
    if not path.is_file():
        return rows
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def combine(states: list[str]) -> str:
    if FAIL in states:
        return FAIL
    if UNVERIFIED in states or INCOMPLETE in states:
        return UNVERIFIED
    return PASS


def criterion(name: str, result: str, detail: str) -> dict:
    return {"criterion": name, "state": result, "detail": detail}


def find_capture(run_dir: Path) -> Path | None:
    collection = read_json(run_dir / "collection.json") or {}
    target = collection.get("captureTarget")
    if isinstance(target, str):
        candidate = Path(target)
        if candidate.is_dir():
            return candidate
    for base in (run_dir, run_dir / "trace"):
        if base.is_dir():
            candidates = sorted(
                (path for path in base.iterdir()
                 if path.is_dir() and path.name.startswith("engine-scroll-qualification-")),
                key=lambda path: path.name,
            )
            if candidates:
                return candidates[-1]
    return None


def motion_key(row: dict) -> str | None:
    key = row.get("motionKey")
    if isinstance(key, str) and key:
        return key
    anchor = row.get("anchorIdentity")
    if isinstance(anchor, dict):
        page = anchor.get("pageIdentity") or {}
        return f"{page.get('pageKey')}@{anchor.get('sourceYQ32')}/{anchor.get('viewportOffsetUnits')}"
    first = row.get("firstVisiblePlacement")
    if isinstance(first, dict):
        page = first.get("pageIdentity") or {}
        return f"{page.get('pageKey')}:{first.get('sourceTop')}:{first.get('topPx')}"
    return None


def frame_scan(frames: list[dict]) -> dict:
    """Validate display frames globally: invalid, duplicate, non-monotonic, coverage counts."""
    scopes: dict[str, dict] = {}
    invalid = duplicate = non_monotonic = 0
    submitted = len(frames)
    valid = 0
    for row in frames:
        if row.get("timestampKind") != DISPLAY_PRESENT or not row.get("swapSucceeded"):
            continue
        presented = row.get("timestampNanos") or 0
        submitted_at = row.get("submittedAtNanos") or 0
        if presented <= 0 or presented < submitted_at:
            invalid += 1
            continue
        scope_key = (row.get("rendererId"), row.get("sessionId"), row.get("surfaceEpoch"))
        scope = scopes.setdefault(str(scope_key), {"seen": set(), "last": None})
        previous = scope["last"]
        if presented in scope["seen"] or previous == presented:
            duplicate += 1
            continue
        if previous is not None and presented < previous:
            non_monotonic += 1
            continue
        scope["seen"].add(presented)
        scope["last"] = presented
        valid += 1
    return {"submitted": submitted, "validDisplay": valid, "pendingDisplay": submitted - valid,
            "invalid": invalid, "duplicate": duplicate, "nonMonotonic": non_monotonic}


def measured_stage_counts(frames: list[dict], stages: list[dict]) -> dict[str, dict]:
    """Raw measured-window movement per targeted stage, unique tokens and direction changes."""
    results: dict[str, dict] = {}
    for stage in stages:
        name = stage.get("stage")
        if name not in TARGETED_STAGES:
            continue
        start = stage.get("startedAtNanos") or 0
        end = stage.get("endedAtNanos") or 0
        measured_from = stage.get("measuredFromNanos") or 0
        if measured_from == 0 or end <= start:
            results[name] = {"measured": None, "directionChanges": None}
            continue
        selected = sorted(
            (row for row in frames
             if start <= (row.get("submittedAtNanos") or 0) <= end
             and (row.get("submittedAtNanos") or 0) >= measured_from),
            key=lambda row: row.get("submittedAtNanos") or 0,
        )
        measured = 0
        direction_changes = 0
        seen_tokens: set = set()
        last_key = None
        last_coordinate = None
        last_sign = 0
        for row in selected:
            if row.get("timestampKind") != DISPLAY_PRESENT or not row.get("swapSucceeded"):
                continue
            presented = row.get("timestampNanos") or 0
            if presented <= 0 or presented < (row.get("submittedAtNanos") or 0):
                continue
            if (row.get("visiblePlacementCount") or 0) <= 0:
                continue
            key = motion_key(row)
            if key is None or key == last_key:
                last_key = key
                continue
            token = row.get("token")
            last_key = key
            if token in seen_tokens:
                continue
            seen_tokens.add(token)
            measured += 1
            coordinate = row.get("motionCoordinate")
            if isinstance(coordinate, (int, float)) and last_coordinate is not None and coordinate != last_coordinate:
                sign = 1 if coordinate > last_coordinate else -1
                if last_sign != 0 and sign != last_sign:
                    direction_changes += 1
                last_sign = sign
            if isinstance(coordinate, (int, float)):
                last_coordinate = coordinate
        results[name] = {"measured": measured, "directionChanges": direction_changes}
    return results


def stage_coverage(capture: Path | None, terminal_proof: dict | None = None) -> dict:
    if capture is None:
        return criterion("moving_stage_coverage", UNVERIFIED, "capture directory not found")
    evidence = read_json(capture / "stage-evidence.json")
    frames = read_jsonl(capture / "frames.jsonl")
    if evidence is None:
        return criterion("moving_stage_coverage", UNVERIFIED, "stage-evidence.json missing")
    if not frames:
        return criterion("moving_stage_coverage", UNVERIFIED, "frames.jsonl missing or empty")
    stages = evidence.get("stages") or []
    by_name = {stage.get("stage"): stage for stage in stages}
    raw = measured_stage_counts(frames, stages)
    details = []
    states = []
    for name in TARGETED_STAGES:
        stage = by_name.get(name)
        if stage is None:
            states.append(UNVERIFIED)
            details.append(f"{name}: absent")
            continue
        if int(stage.get("measuredFromNanos") or 0) == 0:
            if name == "NEXT_BOUNDARY" and terminal_proof_matches(terminal_proof, evidence):
                states.append(PASS)
                details.append(f"{name}: terminal episode, no next neighbour to observe (vacuous)")
                continue
            states.append(UNVERIFIED)
            details.append(f"{name}: measured window never activated")
            continue
        live = int(stage.get("measuredMovingDisplayFrames") or 0)
        live_transitions = int(stage.get("measuredDirectionChanges") or 0)
        raw_row = raw.get(name) or {}
        raw_measured = raw_row.get("measured")
        raw_transitions = raw_row.get("directionChanges")
        if live < TARGET_MOVING_FRAMES:
            states.append(FAIL)
            details.append(f"{name}: measured {live} < {TARGET_MOVING_FRAMES} unique moving frames")
            continue
        if live_transitions < MIN_DIRECTIONAL_TRANSITIONS:
            states.append(FAIL)
            details.append(f"{name}: {live_transitions} < {MIN_DIRECTIONAL_TRANSITIONS} direction changes")
            continue
        if raw_measured is None:
            states.append(UNVERIFIED)
            details.append(f"{name}: raw measured window missing")
            continue
        tolerance = max(2, live // 100)
        if abs(live - raw_measured) > tolerance:
            states.append(FAIL)
            details.append(f"{name}: live {live} contradicts raw measured {raw_measured}")
        elif abs(live_transitions - raw_transitions) > max(1, MIN_DIRECTIONAL_TRANSITIONS):
            states.append(FAIL)
            details.append(f"{name}: live transitions {live_transitions} contradict raw {raw_transitions}")
        else:
            states.append(PASS)
            details.append(
                f"{name}: {live} measured moving frames, {live_transitions} direction changes "
                f"(raw {raw_measured}/{raw_transitions})")
    return criterion("moving_stage_coverage", combine(states), "; ".join(details))


def loading_attempts_check(capture: Path | None) -> dict:
    if capture is None:
        return criterion("prefirst_viewport_input_attempts", UNVERIFIED, "capture directory not found")
    attempts = read_jsonl(capture / "loading-attempts.jsonl")
    if not attempts:
        return criterion("prefirst_viewport_input_attempts", UNVERIFIED,
                         "no pre-first-viewport attempts recorded")
    evidence = read_json(capture / "stage-evidence.json") or {}
    milestone = (evidence.get("loading") or {}).get("firstCompleteViewportSubmittedAtNanos")
    replay = []
    for row in attempts:
        at = row.get("atNanos") or 0
        observed = row.get("milestoneObservedAtNanos")
        if isinstance(milestone, int) and at >= milestone:
            replay.append(row.get("ordinal"))
    if replay:
        return criterion("prefirst_viewport_input_attempts", FAIL,
                         f"attempts continued after the first viewport milestone: {replay[:8]}")
    surface_ready = sum(1 for row in attempts if row.get("surfaceReady"))
    return criterion("prefirst_viewport_input_attempts", PASS,
                     f"{len(attempts)} pre-milestone attempts; {surface_ready} with a ready surface")


def effective_fence_scan(frames: list[dict], sidecar: dict | None) -> dict | None:
    """Coverage over the validated effective presentations written by the extractor.

    The capture-side frame stream can only carry the present evidence known at callback time
    (late kernel fence signals arrive after the frame row is exported and stay latch-only), so
    the extractor promotes a latch frame only when one unique single-scope late fence proves
    it. This scan accepts exactly those validated statuses."""
    if not isinstance(sidecar, dict) or not isinstance(sidecar.get("presentations"), list):
        return None
    records = {row.get("frameToken"): row for row in sidecar["presentations"]
               if isinstance(row, dict) and row.get("frameToken") is not None}
    accepted = {"RAW_DISPLAY_PRESENT", "UNIQUE_LATE_SIGNAL"}
    scan = {"total": 0, "effective": 0, "withheld": 0, "duplicate": 0, "reasons": {}}
    seen: set = set()
    for frame in frames:
        scan["total"] += 1
        token = frame.get("token")
        record = records.get(token) if token is not None else None
        value = (record or {}).get("effectiveTimestampNanos")
        submitted = frame.get("submittedAtNanos")
        if (record is None or record.get("status") not in accepted
                or record.get("effectiveKind") != "DISPLAY_PRESENT"
                or not isinstance(value, int) or value <= 0
                or (isinstance(submitted, int) and value < submitted)):
            scan["withheld"] += 1
            reason = (record or {}).get("status") or "NO_RECORD"
            scan["reasons"][reason] = scan["reasons"].get(reason, 0) + 1
            continue
        if value in seen:
            scan["duplicate"] += 1
        seen.add(value)
        scan["effective"] += 1
    return scan


def fence_coverage_check(capture: Path | None, run_dir: Path | None = None) -> dict:
    if capture is None:
        return criterion("display_fence_coverage", UNVERIFIED, "capture directory not found")
    frames = read_jsonl(capture / "frames.jsonl")
    if not frames:
        return criterion("display_fence_coverage", UNVERIFIED, "frames.jsonl missing or empty")
    # A frame the renderer cancelled at close is a terminal non-presentation; every other
    # successful submission must carry a display observation.
    required = [row for row in frames if row.get("timestampKind") != "CANCELLED"]
    scan = frame_scan(required)
    pending = scan["pendingDisplay"]
    if pending > 0 and run_dir is not None:
        sidecar = read_json(run_dir / "effective-presentations.json")
        effective = effective_fence_scan(required, sidecar)
        if effective is not None:
            if (sidecar or {}).get("traceDataLoss"):
                return criterion("display_fence_coverage", UNVERIFIED,
                                 f"{pending}/{scan['submitted']} frames latch-only; trace data_loss "
                                 "withholds validated late-fence promotion (fix cleanliness first)")
            if effective["effective"] == effective["total"] and not effective["duplicate"]:
                return criterion("display_fence_coverage", PASS,
                                 f"{effective['effective']}/{effective['total']} frames carry a unique "
                                 "positive effective DISPLAY_PRESENT (raw or validated single-scope late "
                                 f"fence; raw latch-only {pending}/{scan['submitted']})")
            reasons = ", ".join(f"{key}={value}"
                                for key, value in sorted(effective["reasons"].items())[:6])
            return criterion("display_fence_coverage", UNVERIFIED,
                             f"{effective['withheld']}/{effective['total']} frames lack an effective "
                             "DISPLAY_PRESENT" + (f" ({reasons})" if reasons else "")
                             + (f"; duplicate effective timestamps {effective['duplicate']}"
                                if effective["duplicate"] else ""))
    if pending > 0:
        return criterion("display_fence_coverage", UNVERIFIED,
                         f"{pending}/{scan['submitted']} submitted frames have no usable "
                         "DISPLAY_PRESENT timestamp; pending fences are missing evidence")
    return criterion("display_fence_coverage", PASS,
                     f"{scan['validDisplay']}/{scan['submitted']} frames carry unique positive fences")


def integrity_check(capture: Path | None) -> dict:
    if capture is None:
        return criterion("ring_integrity", UNVERIFIED, "capture directory not found")
    evidence = read_json(capture / "stage-evidence.json")
    if evidence is None:
        return criterion("ring_integrity", UNVERIFIED, "stage-evidence.json missing")
    integrity = evidence.get("integrity") or {}
    problems = []
    if int(integrity.get("frameLostCount") or 0):
        problems.append(f"frameLostCount={integrity.get('frameLostCount')}")
    if int(integrity.get("inputLostCount") or 0):
        problems.append(f"inputLostCount={integrity.get('inputLostCount')}")
    if integrity.get("motionHistoryOverwritten"):
        problems.append("motion ring overwritten")
    if integrity.get("gestureWindowsTruncated"):
        problems.append("gesture windows truncated")
    if integrity.get("windowFramesDropped"):
        problems.append("HWUI window frame reports dropped")
    for key in ("frameInvalidDisplayPresentTimestamps", "frameDuplicateNativeTimestamps",
                "frameNonMonotonicNativeTimestamps"):
        if int(integrity.get(key) or 0):
            problems.append(f"{key}={integrity.get(key)}")

    frames = read_jsonl(capture / "frames.jsonl")
    inputs = read_jsonl(capture / "inputs.jsonl")
    motion = read_jsonl(capture / "motion.jsonl")
    windows = read_jsonl(capture / "motion-windows.jsonl")
    renderer_close = read_json(capture / "renderer-close.json") or {}
    input_close = read_json(capture / "input-close.json") or {}
    motion_close = read_json(capture / "motion-close.json") or {}
    if frames:
        scan = frame_scan(frames)
        for key, value in (("frameInvalidDisplayPresentTimestamps", scan["invalid"]),
                           ("frameDuplicateNativeTimestamps", scan["duplicate"]),
                           ("frameNonMonotonicNativeTimestamps", scan["nonMonotonic"])):
            if value:
                problems.append(f"raw {key}={value}")
            recorded = int(integrity.get(key) or 0)
            if recorded != value:
                problems.append(f"{key} recorded {recorded} != raw {value}")
    if not frames:
        problems.append("frames.jsonl missing or empty")
    if renderer_close.get("submittedFrameCount") != len(frames):
        problems.append(f"renderer close {renderer_close.get('submittedFrameCount')} != frames {len(frames)}")
    if renderer_close.get("deliveredObservationCount") != len(frames):
        problems.append("renderer delivered observations != exported frames")
    last_input_revision = inputs[-1].get("inputRevision") if inputs else None
    if input_close.get("observationCount") != len(inputs):
        problems.append(f"input close {input_close.get('observationCount')} != inputs {len(inputs)}")
    if inputs and input_close.get("receivedInputCount") != last_input_revision:
        problems.append("input close received count != last input revision")
    if motion_close.get("observationCount") != len(motion):
        problems.append(f"motion close {motion_close.get('observationCount')} != motion {len(motion)}")
    if motion_close.get("windowCount") != len(windows):
        problems.append("motion close window count != exported windows")
    if motion_close.get("historyOverwritten") is not False:
        problems.append("motion close reports overwrite or is missing")
    if problems:
        return criterion("ring_integrity", FAIL, "; ".join(problems))
    return criterion("ring_integrity", PASS, "close proofs consistent; no lost observations")


def terminal_proof_matches(proof: dict | None, evidence: dict) -> bool:
    """A terminal-episode proof is valid when it names the same episode as the capture,
    declares the episode terminal, records the plan-store digest, and carries no next key."""
    if not isinstance(proof, dict) or proof.get("terminal") is not True:
        return False
    plan = proof.get("plan")
    if not isinstance(plan, dict):
        return False
    if not isinstance(plan.get("planSha256"), str) or not plan["planSha256"]:
        return False
    if plan.get("nextKey"):
        return False
    episode_key = plan.get("episodeKey")
    if not isinstance(episode_key, str) or not episode_key:
        return False
    return episode_key in json.dumps(evidence.get("episode") or {})


def boundary_check(capture: Path | None, require_boundary: bool,
                   terminal_proof: dict | None = None) -> dict:
    if capture is None:
        return criterion("next_boundary_observed", UNVERIFIED, "capture directory not found")
    evidence = read_json(capture / "stage-evidence.json")
    if evidence is None:
        return criterion("next_boundary_observed", UNVERIFIED, "stage-evidence.json missing")
    boundary = evidence.get("boundary") or {}
    stages = {stage.get("stage"): stage for stage in (evidence.get("stages") or [])}
    if "NEXT_BOUNDARY" not in stages:
        return criterion("next_boundary_observed", UNVERIFIED,
                         "NEXT_BOUNDARY stage absent (cross-next-boundary flag not passed)")
    stage_start = stages["NEXT_BOUNDARY"].get("startedAtNanos") or 0
    fresh = all(
        isinstance(boundary.get(key), (int, float)) and boundary.get(key) >= stage_start > 0
        for key in ("firstNextSourceAtNanos", "firstNextAnchorAtNanos",
                    "completeNextFrameAtNanos", "shareViewportAtNanos")
    )
    observed = boundary.get("firstNextSourceToken") is not None and \
        boundary.get("firstNextAnchorToken") is not None and \
        bool(boundary.get("currentAndNextShareViewport")) and \
        bool(boundary.get("completeNextFrameObserved"))
    detail = json.dumps(boundary, sort_keys=True)
    if observed and fresh:
        return criterion("next_boundary_observed", PASS, detail)
    if observed and not fresh:
        return criterion("next_boundary_observed", FAIL,
                         "boundary tokens predate this stage; historical crossing cannot satisfy it")
    if terminal_proof_matches(terminal_proof, evidence):
        return criterion("next_boundary_observed", PASS,
                         "terminal episode: plan store has no next neighbour (" +
                         json.dumps(terminal_proof.get("plan"), sort_keys=True) + ")")
    return criterion("next_boundary_observed", FAIL if require_boundary else UNVERIFIED, detail)


def cache_check(run: dict) -> dict:
    inventory = run.get("cacheInventory")
    if not isinstance(inventory, dict):
        return criterion("cache_inventory_recorded", UNVERIFIED, "cache-inventory.json missing")
    if inventory.get("available") is False:
        return criterion("cache_inventory_recorded", UNVERIFIED,
                         f"cache inventory unavailable: {inventory.get('reason')}")
    names = inventory.get("pageCacheNames")
    if not isinstance(names, list) or int(inventory.get("count", -1)) != len(names):
        return criterion("cache_inventory_recorded", FAIL, "cache inventory malformed")
    if bool(inventory.get("deviceCacheGloballyCleared")):
        return criterion("cache_inventory_recorded", FAIL,
                         "record claims a global cache clear that no step performed")
    return criterion("cache_inventory_recorded", PASS, f"{len(names)} cached page identities recorded")


def _cold_module():
    try:
        import importlib
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        return importlib.import_module("qualify_engine_scroll_cold")
    except Exception:  # noqa: BLE001 - checker degrades to UNVERIFIED without the helper
        return None


def _scoped_cold_criterion(run_dir: Path, state: dict) -> dict | None:
    scope = state.get("scope")
    if not isinstance(scope, dict):
        return None
    module = _cold_module()
    if module is None:
        return criterion("natural_cold_state", UNVERIFIED, "scoped cold helper unavailable")
    fields = ("sourceId", "seriesKey", "requestedEpisodeKey", "scopeEpisodes", "pageCacheKeys",
              "planFiles", "stagingFiles", "journalFiles")
    if any(key not in scope for key in fields):
        return criterion("natural_cold_state", UNVERIFIED, "scoped cache-state is incomplete")
    canonical = json.dumps({key: scope[key] for key in fields}, sort_keys=True, separators=(",", ":"))
    if scope.get("scopeSha256") != hashlib.sha256(canonical.encode("utf-8")).hexdigest():
        return criterion("natural_cold_state", FAIL, "scoped cache-state hash does not recompute")
    installed = read_json(run_dir.parent / "installed.json") or {}
    case = installed.get("case") or {}
    requested = (case.get("sourceId"), case.get("seriesKey"), case.get("episodeKey"))
    if requested != (scope["sourceId"], scope["seriesKey"], scope["requestedEpisodeKey"]):
        return criterion("natural_cold_state", FAIL,
                         "scope does not match the requested episode recorded at install time")
    inventory = read_json(run_dir / "scope-inventory-before.json")
    if not isinstance(inventory, dict):
        return criterion("natural_cold_state", UNVERIFIED, "scope-inventory-before.json missing")
    if inventory.get("scopeSha256") != scope.get("scopeSha256"):
        return criterion("natural_cold_state", FAIL, "scope inventory hash does not match the scope")
    if inventory.get("entries") or inventory.get("scopedEntriesAbsent") is not True:
        return criterion("natural_cold_state", FAIL, "scoped cache entries were present before the run")
    if int(state.get("scopedCachePagesBefore") or 0) != 0 or state.get("scopedEntriesAbsentBefore") is not True:
        return criterion("natural_cold_state", FAIL, "scoped absence was not recorded before the run")
    entry_state = state.get("entryState")
    if not isinstance(entry_state, dict) or entry_state.get("databaseRestored") is not True \
            or entry_state.get("prepareP0Applied") is not True \
            or entry_state.get("bookmarksPreserved") is not True \
            or not entry_state.get("preparedDatabaseSha256"):
        return criterion("natural_cold_state", UNVERIFIED,
                         "per-run starting position is not proven (database/prepare_p0/bookmarks)")
    baseline = read_json(run_dir.parent / "cache-baseline.json")
    final = read_json(run_dir.parent / "cache-final-inventory.json")
    if not isinstance(baseline, dict) or not isinstance(final, dict):
        return criterion("natural_cold_state", UNVERIFIED, "cache baseline/final inventories missing")
    for label, value in (("baseline", baseline), ("final", final)):
        roots = value.get("roots")
        if not isinstance(roots, dict):
            return criterion("natural_cold_state", FAIL, f"{label} inventory is malformed")
        digest = module.roots_digest({root: {"entries": roots[root].get("entries", [])}
                                      for root in roots})
        if value.get("inventorySha256") != digest:
            return criterion("natural_cold_state", FAIL, f"{label} inventory digest does not recompute")
    for root in module.ROOT_ORDER:
        baseline_entries = {entry["name"]: entry["sha256"]
                            for entry in baseline["roots"].get(root, {}).get("entries", [])}
        final_entries = {entry["name"]: entry["sha256"]
                         for entry in final["roots"].get(root, {}).get("entries", [])}
        if set(baseline_entries) != set(final_entries) or any(
                baseline_entries[name] != final_entries[name] for name in baseline_entries):
            return criterion("natural_cold_state", FAIL, f"cache root {root} was not restored to baseline")
    if baseline.get("inventorySha256") != final.get("inventorySha256"):
        return criterion("natural_cold_state", FAIL,
                         "final cache inventory does not match the baseline metadata/hashes")
    restoration = read_json(run_dir.parent / "scope-restoration.json")
    if not isinstance(restoration, dict) or restoration.get("scopeRestorationVerified") is not True \
            or restoration.get("globalBaselineExact") is not True:
        return criterion("natural_cold_state", UNVERIFIED, "scope restoration proof is missing")
    if restoration.get("scopeSha256") != scope.get("scopeSha256"):
        return criterion("natural_cold_state", FAIL, "scope restoration hash does not match")
    if state.get("unrelatedEntriesUnchanged") is not True \
            or state.get("scopeRestorationVerified") is not True:
        return criterion("natural_cold_state", FAIL,
                         "scoped cache-state restoration flags contradict the proof")
    scoped_entries = len(scope["pageCacheKeys"]) + len(scope["planFiles"]) + len(scope["stagingFiles"])
    return criterion("natural_cold_state", PASS,
                     f"case-cold (scoped): {scoped_entries} verified scoped entries absent at launch; "
                     "global cache preserved")


def cache_state_check(run_dir: Path) -> dict:
    """Force-stop is process-cold only; scoped case-cold needs full hash-backed proofs."""
    state = read_json(run_dir / "cache-state.json")
    if not isinstance(state, dict):
        return criterion("natural_cold_state", UNVERIFIED,
                         "cache-state.json missing; process-cold state not recorded")
    if state.get("deviceCacheCleared"):
        return criterion("natural_cold_state", FAIL,
                         "record claims a cache clear that no authorized step performed")
    if state.get("forceStopOnly") is not True:
        return criterion("natural_cold_state", UNVERIFIED,
                         "run is not labeled as force-stop process-cold only")
    if state.get("naturalColdClaimable") is True and int(state.get("cachePagesBefore") or 0) == 0:
        return criterion("natural_cold_state", PASS,
                         "page cache was empty before launch; force-stop only")
    scoped = _scoped_cold_criterion(run_dir, state)
    if scoped is not None:
        return scoped
    return criterion("natural_cold_state", UNVERIFIED,
                     f"page cache populated before launch "
                     f"({state.get('cachePagesBefore')} pages); natural cold start not established")


def cache_provenance_check(run_dir: Path) -> dict:
    """Before/after inventories must carry sizes plus content hashes with a valid digest."""
    inventories = {}
    for label, name in (("before", "cache-inventory-before.json"),
                        ("after", "cache-inventory-after.json")):
        value = read_json(run_dir / name)
        if not isinstance(value, dict):
            return criterion("cache_state_provenance", UNVERIFIED,
                             f"{label} page-cache inventory missing; provenance not recorded")
        inventories[label] = value
    for label, inventory in inventories.items():
        entries = inventory.get("entries")
        if not isinstance(entries, list) or int(inventory.get("count", -1)) != len(entries):
            return criterion("cache_state_provenance", FAIL,
                             f"{label} inventory entries malformed")
        if entries and not inventory.get("contentHashesAvailable"):
            return criterion("cache_state_provenance", UNVERIFIED,
                             f"{label} inventory lacks per-entry sizes and content hashes")
        canonical = json.dumps(sorted(entries, key=lambda item: item.get("name")),
                               sort_keys=True, separators=(",", ":"))
        digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        if inventory.get("inventorySha256") != digest:
            return criterion("cache_state_provenance", FAIL,
                             f"{label} inventorySha256 contradicts entry metadata")
    before_count = int(inventories["before"].get("count") or 0)
    after_count = int(inventories["after"].get("count") or 0)
    return criterion("cache_state_provenance", PASS,
                     f"{before_count} pages before, {after_count} pages after; metadata hashed")


def raw_binding_check(capture: Path | None) -> dict:
    if capture is None:
        return criterion("raw_input_binding_honest", UNVERIFIED, "capture directory not found")
    summary = read_json(capture / "summary.json") or {}
    if summary.get("rawInputBindingAvailable"):
        return criterion("raw_input_binding_honest", FAIL,
                         "summary claims raw input binding without the host hook")
    rows = read_jsonl(capture / "inputs.jsonl")
    if not rows:
        return criterion("raw_input_binding_honest", UNVERIFIED, "inputs.jsonl missing")
    fabricated = [row for row in rows if row.get("rawBindingAvailable") and
                  row.get("rawEventTimeNanos") is None]
    if fabricated:
        return criterion("raw_input_binding_honest", FAIL,
                         f"{len(fabricated)} rows claim raw binding without a raw timestamp")
    return criterion("raw_input_binding_honest", PASS,
                     "dispatch-order gestures are not bound to receipts; raw clock explicitly absent")


def analyzer_states(run_dir: Path) -> tuple[dict, str]:
    analysis = read_json(run_dir / "analysis.json")
    if analysis is None:
        return {}, "analysis.json missing (run analyze-presented-motion.py first)"
    runs = analysis.get("runs") or []
    if not runs or not isinstance(runs[0].get("viewer"), dict):
        return {}, "analysis.json has no viewer report"
    states = {}
    for item in runs[0]["viewer"].get("criteria", []):
        states[item.get("criterion")] = item.get("state")
    return states, ""


def check_run(run_dir: Path, require_boundary: bool) -> dict:
    capture = find_capture(run_dir)
    terminal_proof = read_json(run_dir / "terminal-episode-proof.json")
    if terminal_proof is None:
        terminal_proof = read_json(run_dir.parent / "terminal-episode-proof.json")
    criteria = [
        stage_coverage(capture, terminal_proof),
        integrity_check(capture),
        boundary_check(capture, require_boundary, terminal_proof),
        loading_attempts_check(capture),
        fence_coverage_check(capture, run_dir),
        raw_binding_check(capture),
    ]
    run_json = read_json(run_dir / "cache-inventory.json")
    criteria.append(cache_check({"cacheInventory": run_json}))
    criteria.append(cache_state_check(run_dir))
    criteria.append(cache_provenance_check(run_dir))
    states, note = analyzer_states(run_dir)
    if not states:
        criteria.append(criterion("analyzer_report", UNVERIFIED, note))
    else:
        for name, state in states.items():
            if name in RUN_SET_CRITERIA:
                continue
            criteria.append(criterion(f"analyzer:{name}", state, "from analyze-presented-motion.py"))
    design_pending = sorted(
        item["criterion"] for item in criteria
        if item["criterion"].split("analyzer:", 1)[-1] in DESIGN_PENDING_CRITERIA)
    gating_states = [item["state"] for item in criteria
                     if item["criterion"] not in design_pending]
    result = combine(gating_states)
    return {
        "runDir": str(run_dir),
        "captureDir": str(capture) if capture else None,
        "state": result,
        "criteria": criteria,
        "designPending": design_pending,
        "physicalPresentationVerified": False,
        "corpusCredit": 0,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, action="append", default=[],
                        help="artifact run directory; repeat for each cold run")
    parser.add_argument("--allow-missing-boundary", action="store_true",
                        help="downgrade an absent NEXT_BOUNDARY stage from FAIL to UNVERIFIED")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    if not args.run:
        parser.error("--run is required")
    require_boundary = not args.allow_missing_boundary
    report = {
        "schemaVersion": SCHEMA_VERSION,
        "checker": Path(__file__).name,
        "movingEvidenceSource": "raw frames.jsonl engine DISPLAY_PRESENT frames",
        "runs": [check_run(run, require_boundary) for run in args.run],
    }
    run_states = [run["state"] for run in report["runs"]]
    aggregate = []
    prepared_hashes = []
    for run in report["runs"]:
        state = read_json(Path(run["runDir"]) / "cache-state.json") or {}
        if isinstance(state.get("scope"), dict):
            entry_state = state.get("entryState") or {}
            prepared_hashes.append(entry_state.get("preparedDatabaseSha256"))
    if prepared_hashes:
        if any(not value for value in prepared_hashes):
            aggregate.append(criterion("per_run_entry_position", UNVERIFIED,
                                       "prepared database hash missing for a scoped run"))
        elif len(set(prepared_hashes)) == 1:
            aggregate.append(criterion("per_run_entry_position", PASS,
                                       f"all scoped runs entered from {prepared_hashes[0]}"))
        else:
            aggregate.append(criterion("per_run_entry_position", FAIL,
                                       f"scoped runs entered from different positions: {prepared_hashes}"))
    trailing = run_states[-MIN_CONSECUTIVE_COLD_RUNS:]
    if len(run_states) >= MIN_CONSECUTIVE_COLD_RUNS and all(state == PASS for state in trailing):
        aggregate.append(criterion("three_consecutive_cold_runs", PASS,
                                   f"{MIN_CONSECUTIVE_COLD_RUNS} consecutive scoped-cold runs qualified "
                                   "with every gateable criterion PASS"))
    elif FAIL in run_states:
        aggregate.append(criterion("three_consecutive_cold_runs", FAIL,
                                   "a scoped-cold run contradicted a gated criterion"))
    else:
        aggregate.append(criterion("three_consecutive_cold_runs", UNVERIFIED,
                                   f"{len(run_states)} run(s) present, "
                                   f"{MIN_CONSECUTIVE_COLD_RUNS} consecutive qualifying runs required"))
    report["runStates"] = run_states
    report["aggregateCriteria"] = aggregate
    overall_states = run_states + [item["state"] for item in aggregate]
    report["overall"] = FAIL if FAIL in overall_states else (
        PASS if overall_states and all(state == PASS for state in overall_states) else UNVERIFIED)
    report["qualified"] = report["overall"] == PASS
    report["physicalPresentationVerified"] = False
    report["requiredRawHook"] = (
        "raw MotionEvent receive timestamps + raw movement binding (input_binding worker)")
    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(json.dumps({"overall": report["overall"], "runStates": run_states}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
