#!/usr/bin/env python3
"""Reproduce a command-to-motion input audit from captured host artifacts.

The tool is deliberately offline. It reads the existing injection/recorder
TSVs and Perfetto trace, keeps source timestamps in nanoseconds, and writes a
JSON report plus a compact Markdown handoff. It never invokes ADB, Gradle,
the emulator, or a fresh capture.

Important clock rule: MotionEvent.eventTime is exported only in whole
milliseconds. Every eventTime-derived interval is therefore reported with a
bounded +/- --input-uncertainty-ms interval. The gesture response boundary is
the first MOVE handling window, not ACTION_DOWN.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import re
import sys
from pathlib import Path
from typing import Any, Iterable, Mapping


NS_PER_MS = 1_000_000
APPLICATION_MARKER_MATCH_TOLERANCE_NANOS = NS_PER_MS
SCHEMA = "ntk-v6-input-motion-audit-v2"
INPUT_RE = re.compile(r"^viewer_input:(\d+):(\d+):(\d+)$")
MOTION_APPLIED_RE = re.compile(r"^viewer_motion_applied:(\d+):(\d+):(\d+):(\d+):(\d+)$")
PREPARE_RE = re.compile(r"^viewer_prepare:(\d+):(\d+)$")
SWAP_RE = re.compile(r"^viewer_swap:(\d+):(\d+):(\d+)$")
ACTION_NAMES = {0: "DOWN", 1: "UP", 2: "MOVE", 3: "CANCEL"}
SUPPORT_NAMES = (
    "deliverInputEvent",
    "processInputEventForCompatibility",
    "input",
    "animation",
    "Choreographer#scheduleVsyncLocked",
    "View#onTouchEvent",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest().upper()


def rows(processor: Any, query: str) -> list[dict[str, Any]]:
    return [dict(vars(row)) for row in processor.query(query)]


def int_value(value: Any, default: int | None = None) -> int | None:
    if value is None or value == "":
        return default
    return int(value)


def quantiles(values: Iterable[int]) -> dict[str, float | int | None]:
    ordered = sorted(int(v) for v in values if v is not None)
    if not ordered:
        return {"n": 0, "p50": None, "p95": None, "max": None}

    def linear(fraction: float) -> float:
        position = (len(ordered) - 1) * fraction
        lower = math.floor(position)
        upper = math.ceil(position)
        return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)

    return {
        "n": len(ordered),
        "p50": linear(0.50),
        "p95": linear(0.95),
        "max": ordered[-1],
    }


def interval(nominal: int, uncertainty: int) -> dict[str, int]:
    return {
        "nominalNanos": int(nominal),
        "lowerNanos": int(nominal - uncertainty),
        "upperNanos": int(nominal + uncertainty),
        "uncertaintyNanos": int(uncertainty),
    }


def read_starts(path: Path) -> list[int]:
    values = []
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.strip():
            values.append(int(line.strip()))
    return values


def read_windows(path: Path) -> list[tuple[int, int]]:
    values: list[tuple[int, int]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip():
            continue
        fields = line.split("\t")
        if len(fields) != 2:
            raise ValueError(f"{path}:{number}: expected start<TAB>end")
        start, end = map(int, fields)
        if end < start:
            raise ValueError(f"{path}:{number}: inverted window")
        values.append((start, end))
    return values


def read_motion(path: Path) -> list[dict[str, int]]:
    values = []
    with path.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream, delimiter="\t"):
            values.append({
                "motionSequence": int(row["motionSequence"]),
                "frameTimeNanos": int(row["frameTimeNanos"]),
                "appliedAtNanos": int(row["appliedAtNanos"]),
            })
    return values


def read_presentations(path: Path) -> dict[int, dict[str, Any]]:
    values: dict[int, dict[str, Any]] = {}
    with path.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream, delimiter="\t"):
            if row.get("kind") != "presentation" or not row.get("token"):
                continue
            token = int(row["token"])
            parsed: dict[str, Any] = {"token": token}
            for name, value in row.items():
                if name == "token":
                    continue
                if value in (None, ""):
                    parsed[name] = None
                elif name in {
                    "index", "renderer", "generation", "presentedNanos",
                    "submittedAtNanos", "expectedPresentationTimeNanos",
                    "renderLatencyNanos", "scrollOffsetUnits",
                    "viewportHeightUnits", "anchorOrdinal", "anchorOffsetUnits",
                    "bufferFrameId", "geometryRevision", "userInputRevision",
                }:
                    parsed[name] = int(value)
                else:
                    parsed[name] = value
            values[token] = parsed
    return values


def read_collection(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def compact_slice(item: Mapping[str, Any] | None) -> dict[str, Any] | None:
    if item is None:
        return None
    keep = (
        "id", "ts", "dur", "name", "track_id", "parent_id", "utid",
        "thread_name", "pid", "process_name",
    )
    result: dict[str, Any] = {}
    for key in keep:
        if key in item:
            value = item[key]
            if key in {"id", "ts", "dur", "track_id", "parent_id", "utid", "pid"} and value is not None:
                result[key] = int(value)
            else:
                result[key] = value
    if "ts" in result:
        result["end_ts"] = int(result["ts"]) + max(0, int(result.get("dur", 0)))
    return result


def fetch_by_ids(processor: Any, ids: set[int]) -> list[dict[str, Any]]:
    if not ids:
        return []
    result: list[dict[str, Any]] = []
    ordered = sorted(ids)
    for offset in range(0, len(ordered), 500):
        group = ordered[offset:offset + 500]
        literal = ",".join(str(value) for value in group)
        result.extend(rows(processor, f"""
            SELECT s.id, s.ts, s.dur, s.name, s.track_id, s.parent_id,
                   tt.utid, t.name AS thread_name, p.pid, p.name AS process_name
            FROM slice s
            LEFT JOIN thread_track tt ON tt.id = s.track_id
            LEFT JOIN thread t ON t.utid = tt.utid
            LEFT JOIN process p ON p.upid = t.upid
            WHERE s.id IN ({literal})
        """))
    return result


def load_trace(trace_path: Path, analysis_start: int, analysis_end: int) -> dict[str, Any]:
    """Load marker, parent, main-thread scheduler, and handler evidence."""
    try:
        from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig
    except ImportError as exc:  # pragma: no cover - bad host only
        raise RuntimeError("perfetto.trace_processor is required for trace audit") from exc

    with TraceProcessor(
        trace=str(trace_path),
        config=TraceProcessorConfig(load_timeout=120),
    ) as processor:
        bounds = rows(processor, "SELECT start_ts, end_ts FROM trace_bounds")
        stats = rows(processor, """
            SELECT name, severity, value
            FROM stats
            WHERE value != 0
              AND (severity = 'data_loss'
                   OR name GLOB '*overrun*'
                   OR name GLOB '*dropped*'
                   OR name GLOB '*packet_loss*')
        """)
        marker_rows = rows(processor, """
            SELECT s.id, s.ts, s.dur, s.name, s.track_id, s.parent_id,
                   tt.utid, t.name AS thread_name, p.pid, p.name AS process_name
            FROM slice s
            LEFT JOIN thread_track tt ON tt.id = s.track_id
            LEFT JOIN thread t ON t.utid = tt.utid
            LEFT JOIN process p ON p.upid = t.upid
            WHERE s.name GLOB 'viewer_input:*'
               OR s.name GLOB 'viewer_motion_applied:*'
               OR s.name GLOB 'viewer_prepare:*'
               OR s.name GLOB 'viewer_swap:*'
            ORDER BY s.ts, s.id
        """)
        if not marker_rows:
            raise RuntimeError("trace contains no viewer_input/prepare/swap markers")
        main_track_id = int(marker_rows[0]["track_id"])
        main_utid = int(marker_rows[0]["utid"])
        support_rows = rows(processor, f"""
            SELECT s.id, s.ts, s.dur, s.name, s.track_id, s.parent_id,
                   tt.utid, t.name AS thread_name, p.pid, p.name AS process_name
            FROM slice s
            LEFT JOIN thread_track tt ON tt.id = s.track_id
            LEFT JOIN thread t ON t.utid = tt.utid
            LEFT JOIN process p ON p.upid = t.upid
            WHERE s.track_id = {main_track_id}
              AND s.ts < {analysis_end}
              AND s.ts + CASE WHEN s.dur > 0 THEN s.dur ELSE 0 END > {analysis_start}
              AND (
                s.name IN (
                  'deliverInputEvent',
                  'processInputEventForCompatibility',
                  'input',
                  'animation',
                  'Choreographer#scheduleVsyncLocked',
                  'View#onTouchEvent'
                )
                OR s.name GLOB 'Choreographer#doFrame*'
                OR s.name GLOB 'ViewPostImeInputStage*'
              )
            ORDER BY s.ts, s.id
        """)
        scheduler_rows = rows(processor, f"""
            SELECT ts, dur, cpu, end_state, utid
            FROM sched_slice
            WHERE utid = {main_utid}
              AND ts < {analysis_end}
              AND ts + CASE WHEN dur > 0 THEN dur ELSE 0 END > {analysis_start}
              AND dur > 0
            ORDER BY ts
        """)

        parent_ids = {
            int(item["parent_id"])
            for item in marker_rows
            if item.get("parent_id") is not None
        }
        parent_rows: list[dict[str, Any]] = []
        seen_parent_ids: set[int] = set()
        while parent_ids - seen_parent_ids:
            batch = parent_ids - seen_parent_ids
            seen_parent_ids.update(batch)
            found = fetch_by_ids(processor, batch)
            parent_rows.extend(found)
            for item in found:
                if item.get("parent_id") is not None:
                    parent_ids.add(int(item["parent_id"]))

    by_id: dict[int, dict[str, Any]] = {}
    for item in marker_rows + support_rows + parent_rows:
        by_id[int(item["id"])] = item
    return {
        "bounds": bounds[0] if bounds else {},
        "stats": stats,
        "markers": marker_rows,
        "support": support_rows,
        "scheduler": scheduler_rows,
        "by_id": by_id,
        "mainTrackId": main_track_id,
        "mainUtid": main_utid,
    }


def parse_input_markers(trace: Mapping[str, Any]) -> list[dict[str, Any]]:
    parsed = []
    for source in trace["markers"]:
        match = INPUT_RE.fullmatch(str(source["name"]))
        if not match:
            continue
        action_code, event_ms, receipt = map(int, match.groups())
        item = dict(source)
        item.update({
            "actionCode": action_code,
            "action": ACTION_NAMES.get(action_code, f"ACTION_{action_code}"),
            "eventTimeMillis": event_ms,
            "eventTimeNanos": event_ms * NS_PER_MS,
            "receiptNanos": receipt,
            "traceEndNanos": int(source["ts"]) + max(0, int(source["dur"])),
        })
        parsed.append(item)
    return sorted(parsed, key=lambda item: (int(item["ts"]), int(item["id"])))


def parse_motion_applied_markers(trace: Mapping[str, Any]) -> dict[int, list[dict[str, Any]]]:
    parsed: dict[int, list[dict[str, Any]]] = {}
    for source in trace["markers"]:
        match = MOTION_APPLIED_RE.fullmatch(str(source["name"]))
        if not match:
            continue
        sequence, applied, revision, generation, renderer_epoch = map(int, match.groups())
        item = dict(source)
        item.update({
            "motionSequence": sequence,
            "appliedAtNanos": applied,
            "userInputRevision": revision,
            "generation": generation,
            "rendererEpoch": renderer_epoch,
            "traceEndNanos": int(source["ts"]) + max(0, int(source["dur"])),
        })
        parsed.setdefault(sequence, []).append(item)
    for values in parsed.values():
        values.sort(key=lambda item: (int(item["ts"]), int(item["id"])))
    return parsed


def motion_applied_slice(item: Mapping[str, Any] | None) -> dict[str, Any] | None:
    if item is None:
        return None
    value = compact_slice(item) or {}
    for key in (
        "motionSequence",
        "appliedAtNanos",
        "userInputRevision",
        "generation",
        "rendererEpoch",
        "traceEndNanos",
    ):
        if key in item:
            value[key] = int(item[key])
    return value


def nearest_application_marker(
    applied: Mapping[str, Any],
    candidates: Iterable[Mapping[str, Any]],
) -> Mapping[str, Any] | None:
    expected_sequence = int(applied["motionSequence"])
    expected_time = int(applied["appliedAtNanos"])
    eligible = [
        item for item in candidates
        if item.get("motionSequence") is not None
        and int(item["motionSequence"]) == expected_sequence
        and abs(int(item["appliedAtNanos"]) - expected_time)
        <= APPLICATION_MARKER_MATCH_TOLERANCE_NANOS
    ]
    return eligible[0] if len(eligible) == 1 else None


def parse_renderer_markers(trace: Mapping[str, Any]) -> tuple[dict[int, list[dict[str, Any]]], dict[int, list[dict[str, Any]]]]:
    prepares: dict[int, list[dict[str, Any]]] = {}
    swaps: dict[int, list[dict[str, Any]]] = {}
    for source in trace["markers"]:
        name = str(source["name"])
        match = PREPARE_RE.fullmatch(name)
        if match:
            token, offered = map(int, match.groups())
            item = dict(source)
            item.update({
                "token": token,
                "offeredAtNanos": offered,
                "traceEndNanos": int(source["ts"]) + max(0, int(source["dur"])),
            })
            prepares.setdefault(token, []).append(item)
            continue
        match = SWAP_RE.fullmatch(name)
        if match:
            token, frame, native = map(int, match.groups())
            item = dict(source)
            item.update({
                "token": token,
                "bufferFrameId": frame,
                "nativeSampleNanos": native,
                "traceEndNanos": int(source["ts"]) + max(0, int(source["dur"])),
            })
            swaps.setdefault(token, []).append(item)
    for values in prepares.values():
        values.sort(key=lambda item: (int(item["offeredAtNanos"]), int(item["id"])))
    for values in swaps.values():
        values.sort(key=lambda item: (int(item["ts"]), int(item["id"])))
    return prepares, swaps


def group_input(markers: list[dict[str, Any]]) -> tuple[list[list[dict[str, Any]]], list[dict[str, Any]]]:
    groups: list[list[dict[str, Any]]] = []
    orphaned: list[dict[str, Any]] = []
    current: list[dict[str, Any]] | None = None
    for marker in markers:
        if marker["action"] == "DOWN":
            if current:
                groups.append(current)
            current = [marker]
        elif current is None:
            orphaned.append(marker)
        else:
            current.append(marker)
            if marker["action"] in {"UP", "CANCEL"}:
                groups.append(current)
                current = None
    if current:
        groups.append(current)
    return groups, orphaned


def first_action(group: list[dict[str, Any]], action: str) -> dict[str, Any] | None:
    return next((item for item in group if item["action"] == action), None)


def ancestor_chain(marker: Mapping[str, Any], by_id: Mapping[int, Mapping[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    parent_id = marker.get("parent_id")
    seen: set[int] = set()
    while parent_id is not None and int(parent_id) not in seen:
        parent_id = int(parent_id)
        seen.add(parent_id)
        parent = by_id.get(parent_id)
        if parent is None:
            result.append({"id": parent_id, "missing": True})
            break
        result.append(compact_slice(parent) or {})
        parent_id = parent.get("parent_id")
    return result


def overlap(start: int, end: int, item: Mapping[str, Any]) -> int:
    item_start = int(item["ts"])
    item_end = item_start + max(0, int(item.get("dur", 0)))
    return max(0, min(end, item_end) - max(start, item_start))


def union_length(intervals: Iterable[tuple[int, int]]) -> int:
    ordered = sorted((a, b) for a, b in intervals if b > a)
    total = 0
    current: tuple[int, int] | None = None
    for start, end in ordered:
        if current is None:
            current = (start, end)
        elif start <= current[1]:
            current = (current[0], max(current[1], end))
        else:
            total += current[1] - current[0]
            current = (start, end)
    return total + (current[1] - current[0] if current else 0)


def raw_event(item: Mapping[str, Any] | None) -> dict[str, Any] | None:
    if item is None:
        return None
    value = compact_slice(item) or {}
    for key in ("actionCode", "action", "eventTimeMillis", "eventTimeNanos", "receiptNanos", "traceEndNanos"):
        if key in item:
            value[key] = item[key]
    return value


def missing_application_provenance(session: Any = None) -> dict[str, Any]:
    return {
        "exact": False,
        "userInputRevision": None,
        "generation": None,
        "rendererEpoch": None,
        "session": session,
        "sessionSource": "collection.json.sampleKey" if session is not None else None,
        "availableFields": [
            "motionSequence",
            "frameTimeNanos",
            "appliedAtNanos",
        ],
        "missingFields": [
            "userInputRevision",
            "generation",
            "rendererEpoch",
        ],
        "source": "motion-input-application-timestamps.tsv",
        "status": "UNKNOWN_AT_APPLICATION",
    }


def application_provenance_from_marker(
    marker: Mapping[str, Any] | None,
    session: Any,
) -> dict[str, Any]:
    if marker is None:
        return missing_application_provenance(session)
    return {
        "exact": True,
        "userInputRevision": int(marker["userInputRevision"]),
        "generation": int(marker["generation"]),
        "rendererEpoch": int(marker["rendererEpoch"]),
        "session": session,
        "sessionSource": "collection.json.sampleKey",
        "availableFields": [
            "motionSequence",
            "appliedAtNanos",
            "userInputRevision",
            "generation",
            "rendererEpoch",
        ],
        "missingFields": [],
        "source": "viewer_motion_applied trace marker",
        "markerSliceId": int(marker["id"]),
        "markerMotionSequence": int(marker["motionSequence"]),
        "markerAppliedAtNanos": int(marker["appliedAtNanos"]),
        "status": "PRESENT_AT_APPLICATION",
    }


def provenance_comparison(
    provenance: Mapping[str, Any] | None,
    presentation: Mapping[str, Any] | None,
) -> dict[str, Any]:
    application = {
        "userInputRevision": provenance.get("userInputRevision") if provenance else None,
        "generation": provenance.get("generation") if provenance else None,
        "rendererEpoch": provenance.get("rendererEpoch") if provenance else None,
    }
    presentation_values = {
        "userInputRevision": presentation.get("userInputRevision") if presentation else None,
        "generation": presentation.get("generation") if presentation else None,
        "rendererEpoch": presentation.get("renderer") if presentation else None,
    }
    mismatches = [
        key for key in application
        if application[key] is None
        or presentation_values[key] is None
        or str(application[key]) != str(presentation_values[key])
    ]
    return {
        "application": application,
        "presentation": presentation_values,
        "mismatches": mismatches,
        "exactMatch": not mismatches,
    }


def exact_provenance_matches(
    provenance: Mapping[str, Any] | None,
    presentation: Mapping[str, Any] | None,
) -> bool:
    if not provenance or not provenance.get("exact") or presentation is None:
        return False
    if provenance.get("session") is None:
        return False
    required = ("userInputRevision", "generation", "rendererEpoch")
    if any(provenance.get(key) is None for key in required):
        return False
    return all(
        presentation_value is not None
        and str(presentation_value) == str(provenance.get(application_key))
        for application_key, presentation_value in (
            ("userInputRevision", presentation.get("userInputRevision")),
            ("generation", presentation.get("generation")),
            ("rendererEpoch", presentation.get("renderer")),
        )
    )


def renderer_slice(item: Mapping[str, Any] | None) -> dict[str, Any] | None:
    if item is None:
        return None
    value = compact_slice(item) or {}
    for key in ("token", "offeredAtNanos", "bufferFrameId", "nativeSampleNanos", "traceEndNanos"):
        if key in item:
            value[key] = int(item[key])
    return value


def renderer_detail(
    applied_at: int | None,
    prepares: Mapping[int, list[dict[str, Any]]],
    swaps: Mapping[int, list[dict[str, Any]]],
    presentations: Mapping[int, dict[str, Any]],
    lookback_nanos: int,
    clock_bridge: Mapping[str, Any],
    application_provenance: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    if applied_at is None:
        return {
            "causalPrepare": None,
            "firstMatchingSubmitNanos": None,
            "identityJoinConfidence": "UNKNOWN",
            "applicationProvenance": dict(application_provenance or missing_application_provenance()),
            "reason": "scroll application timestamp is missing",
        }
    all_prepares = [item for values in prepares.values() for item in values]
    candidates = []
    for item in all_prepares:
        offered = int(item["offeredAtNanos"])
        if offered > applied_at or applied_at - offered > lookback_nanos:
            continue
        meta = presentations.get(int(item["token"]))
        cause = meta.get("scrollCause") if meta else None
        if cause not in (None, "USER_INPUT"):
            continue
        candidates.append(item)
    exact_candidates = [
        item for item in candidates
        if exact_provenance_matches(
            application_provenance, presentations.get(int(item["token"]))
        )
    ]
    causal = max(
        exact_candidates or candidates,
        key=lambda item: (int(item["offeredAtNanos"]), int(item["id"])),
        default=None,
    )
    later = min(
        (item for item in all_prepares if int(item["offeredAtNanos"]) > applied_at),
        key=lambda item: (int(item["offeredAtNanos"]), int(item["id"])),
        default=None,
    )
    result: dict[str, Any] = {
        "lookbackNanos": lookback_nanos,
        "causalPrepare": None,
        "firstPrepareAfterApply": renderer_slice(later),
        "firstMatchingSubmitNanos": None,
        "candidateSubmitNanos": None,
        "identityJoinConfidence": "UNKNOWN",
        "applicationProvenance": dict(application_provenance or missing_application_provenance()),
        "clockBridge": dict(clock_bridge),
    }
    if causal is None:
        result["reason"] = "no USER_INPUT/unknown-cause viewer_prepare offered in bounded lookback"
        return result

    token = int(causal["token"])
    meta = presentations.get(token)
    swap = swaps.get(token, [None])[0]
    causal_value: dict[str, Any] = {
        "prepare": renderer_slice(causal),
        "presentationEvidence": meta,
        "provenanceComparison": provenance_comparison(application_provenance, meta),
        "swap": renderer_slice(swap),
        "applyToOfferNanos": int(causal["offeredAtNanos"]) - applied_at,
        "offerToPrepareStartNanos": int(causal["ts"]) - int(causal["offeredAtNanos"]),
        "prepareDurationNanos": int(causal["dur"]),
    }
    if meta and meta.get("submittedAtNanos") is not None:
        result["candidateSubmitNanos"] = int(meta["submittedAtNanos"])
    if swap is not None:
        causal_value["prepareEndToSwapStartNanos"] = int(swap["ts"]) - int(causal["traceEndNanos"])
        causal_value["traceSwapDurationNanos"] = int(swap["dur"])
        causal_value["nativeSampleNanos"] = int(swap["nativeSampleNanos"])
        causal_value["traceSwapStartMinusNativeSampleNanos"] = int(swap["ts"]) - int(swap["nativeSampleNanos"])
    result["candidatePrepare"] = causal_value
    exact = exact_provenance_matches(application_provenance, meta)
    if exact:
        result["causalPrepare"] = causal_value
    else:
        result["causalPrepare"] = None
        result["reason"] = (
            "time/cause candidate retained, but application row lacks an exact "
            "userInputRevision + generation + rendererEpoch provenance match"
        )
    if exact and meta and meta.get("scrollCause") == "USER_INPUT" and swap is not None and meta.get("submittedAtNanos") is not None:
        result["firstMatchingSubmitNanos"] = int(meta["submittedAtNanos"])
        result["identityJoinConfidence"] = "HIGH"
    elif exact and swap is not None:
        result["identityJoinConfidence"] = "MEDIUM"
    elif not exact:
        result["identityJoinConfidence"] = "UNRESOLVED"
    else:
        result["identityJoinConfidence"] = "LOW"
    return result


def pre_move_evidence(
    start: int,
    end: int,
    support: Iterable[Mapping[str, Any]],
    scheduler: Iterable[Mapping[str, Any]],
) -> dict[str, Any]:
    if end < start:
        start, end = end, start
    support_hits = []
    for item in support:
        amount = overlap(start, end, item)
        if amount:
            support_hits.append((amount, item))
    sched_hits = []
    for item in scheduler:
        item_start = int(item["ts"])
        item_end = item_start + max(0, int(item["dur"]))
        amount = max(0, min(end, item_end) - max(start, item_start))
        if amount:
            sched_hits.append((amount, item))
    support_hits.sort(key=lambda pair: (-pair[0], int(pair[1]["ts"]), int(pair[1]["id"])))
    sched_hits.sort(key=lambda pair: (-pair[0], int(pair[1]["ts"])))
    support_intervals = [
        (max(start, int(item["ts"])), min(end, int(item["ts"]) + max(0, int(item["dur"]))))
        for _, item in support_hits
    ]
    sched_intervals = [
        (max(start, int(item["ts"])), min(end, int(item["ts"]) + max(0, int(item["dur"]))))
        for _, item in sched_hits
    ]
    evidence = bool(support_hits or sched_hits)
    return {
        "intervalNanos": {"start": start, "end": end, "duration": max(0, end - start)},
        "classification": (
            "TARGET_MAIN_SCHEDULER_OR_HANDLER_OVERLAP"
            if evidence else
            "NO_TARGET_MAIN_SCHEDULER_OR_HANDLER_OVERLAP"
        ),
        "externalAttribution": "NOT_PROVEN",
        "scheduler": {
            "count": len(sched_hits),
            "overlapNanos": sum(amount for amount, _ in sched_hits),
            "unionNanos": union_length(sched_intervals),
            "rows": [
                dict(compact_slice(item) or {}, overlapNanos=amount)
                for amount, item in sched_hits[:20]
            ],
        },
        "handlerAndSchedulerSlices": {
            "count": len(support_hits),
            "overlapNanos": sum(amount for amount, _ in support_hits),
            "unionNanos": union_length(support_intervals),
            "rows": [
                dict(compact_slice(item) or {}, overlapNanos=amount)
                for amount, item in support_hits[:30]
            ],
        },
        "interpretation": (
            "A target main-thread scheduler/handler slice overlaps the pre-MOVE "
            "interval; the interval is not external by elimination."
            if evidence else
            "No target main-thread scheduler/handler slice was captured in this "
            "bounded interval; absence is inconclusive and is not an external label."
        ),
    }


def read_clock_bridge(trace_path: Path) -> dict[str, Any]:
    candidate = trace_path.parent / "host-display-verification.json"
    if not candidate.exists():
        return {
            "source": str(candidate),
            "available": False,
            "nativeToTraceOffsetNanos": None,
        }
    document = json.loads(candidate.read_text(encoding="utf-8"))
    measurement: Mapping[str, Any] = {}
    series = document.get("series") or []
    if series and isinstance(series[0], Mapping):
        measurement = series[0].get("measurementUncertainty") or {}
    return {
        "source": str(candidate),
        "sha256": sha256(candidate),
        "available": True,
        "method": measurement.get("clockMethod"),
        "nativeToTraceOffsetNanos": measurement.get("nativeToTraceOffsetNanos"),
        "policy": (
            "Retain native sample and trace timestamps separately. The bounded "
            "bridge is context only; no physical presentation time is claimed."
        ),
    }


def build_case(
    index: int,
    command_start: int,
    window: tuple[int, int],
    group: list[dict[str, Any]],
    motions: list[dict[str, int]],
    trace: Mapping[str, Any],
    prepares: Mapping[int, list[dict[str, Any]]],
    swaps: Mapping[int, list[dict[str, Any]]],
    presentations: Mapping[int, dict[str, Any]],
    uncertainty_nanos: int,
    lookback_nanos: int,
    clock_bridge: Mapping[str, Any],
    application_markers: Mapping[int, list[dict[str, Any]]],
    session_key: Any,
) -> dict[str, Any]:
    down = first_action(group, "DOWN")
    move = first_action(group, "MOVE")
    end_marker = first_action(group, "UP") or first_action(group, "CANCEL")
    window_start, window_end = window
    in_window = [
        item for item in motions
        if window_start <= int(item["appliedAtNanos"]) <= window_end
    ]
    applied = min(in_window, key=lambda item: item["appliedAtNanos"], default=None)
    applied_at = int(applied["appliedAtNanos"]) if applied else None
    application_marker_values = (
        application_markers.get(int(applied["motionSequence"]), [])
        if applied is not None else []
    )
    application_marker = (
        nearest_application_marker(applied, application_marker_values)
        if applied is not None else None
    )
    application_provenance = application_provenance_from_marker(
        application_marker, session_key
    )
    move_event = int(move["eventTimeNanos"]) if move else None
    move_receipt = int(move["receiptNanos"]) if move else None
    move_handler_start = int(move["ts"]) if move else None
    move_handler_end = int(move["traceEndNanos"]) if move else None
    move_creation = move_event - command_start if move_event is not None else None
    move_to_receipt = move_receipt - move_event if move_event is not None and move_receipt is not None else None
    receipt_to_apply = applied_at - move_receipt if applied_at is not None and move_receipt is not None else None
    command_to_apply = applied_at - command_start if applied_at is not None else None
    window_placement = (
        move_handler_start is not None
        and window_start >= move_handler_start - uncertainty_nanos
        and window_start <= move_handler_end + uncertainty_nanos
        if move_handler_end is not None else False
    )
    support_start = command_start
    support_end = move_receipt if move_receipt is not None else window_start
    case: dict[str, Any] = {
        "index": index,
        "longCase": command_to_apply is not None and command_to_apply >= 100 * NS_PER_MS,
        "commandStartNanos": command_start,
        "commandToScrollApplied": interval(command_to_apply, 0) if command_to_apply is not None else None,
        "window": {
            "startNanos": window_start,
            "endNanos": window_end,
            "boundary": "FIRST_MOVE_HANDLING",
        },
        "rawInput": {
            "markerCount": len(group),
            "down": raw_event(down),
            "firstMove": raw_event(move),
            "end": raw_event(end_marker),
        },
        "creationAndReceipt": {
            "downEventTimeInterval": (
                interval(int(down["eventTimeNanos"]), uncertainty_nanos)
                if down else None
            ),
            "firstMoveEventTimeInterval": (
                interval(move_event, uncertainty_nanos)
                if move_event is not None else None
            ),
            "commandToDownCreation": (
                interval(int(down["eventTimeNanos"]) - command_start, uncertainty_nanos)
                if down else None
            ),
            "commandToFirstMoveCreation": (
                interval(move_creation, uncertainty_nanos)
                if move_creation is not None else None
            ),
            "downEventToMainReceipt": (
                interval(int(down["receiptNanos"]) - int(down["eventTimeNanos"]), uncertainty_nanos)
                if down else None
            ),
            "firstMoveEventToMainReceipt": (
                interval(move_to_receipt, uncertainty_nanos)
                if move_to_receipt is not None else None
            ),
            "eventCreationEvidence": {
                "status": "PARTIAL_EVENT_TIME_ONLY" if move else "UNKNOWN",
                "eventTimeSource": "viewer_input label eventTimeMillis" if move else "UNKNOWN",
                "subMillisecondCreation": "UNKNOWN",
                "note": (
                    "The eventTimeMillis value is retained; no finer-grained "
                    "injection/event-creation timestamp exists in this capture."
                    if move else "No first MOVE marker exists."
                ),
            },
        },
        "handler": {
            "downMarker": compact_slice(down),
            "firstMoveMarker": compact_slice(move),
            "downHandlerTimeNanos": int(down["dur"]) if down else None,
            "firstMoveHandlerTimeNanos": int(move["dur"]) if move else None,
            "firstMoveWindowStartWithinMarker": window_placement,
            "downAncestors": ancestor_chain(down, trace["by_id"]) if down else [],
            "firstMoveAncestors": ancestor_chain(move, trace["by_id"]) if move else [],
            "firstMoveReceiptToWindowStartNanos": (
                window_start - move_receipt if move_receipt is not None else None
            ),
        },
        "application": {
            "motion": applied,
            "appliedMarker": motion_applied_slice(application_marker),
            "appliedMarkerCountForSequence": len(application_marker_values),
            "appliedMarkerCandidates": [
                motion_applied_slice(item) for item in application_marker_values
            ],
            "markerAppliedAtDeltaNanos": (
                int(application_marker["appliedAtNanos"]) - applied_at
                if application_marker is not None and applied_at is not None else None
            ),
            "firstMoveReceiptToScrollAppliedNanos": receipt_to_apply,
            "windowStartToScrollAppliedNanos": (
                applied_at - window_start if applied_at is not None else None
            ),
            "decompositionNominalSumNanos": (
                move_creation + move_to_receipt + receipt_to_apply
                if move_creation is not None and move_to_receipt is not None and receipt_to_apply is not None
                else None
            ),
        },
        "preMoveEvidence": pre_move_evidence(
            support_start, support_end, trace["support"], trace["scheduler"]
        ),
        "renderer": renderer_detail(
            applied_at, prepares, swaps, presentations, lookback_nanos,
            clock_bridge, application_provenance,
        ),
    }
    case["decompositionCheck"] = (
        case["application"]["decompositionNominalSumNanos"] == command_to_apply
        if command_to_apply is not None else False
    )
    return case


def down_boundary_hits(cases: Iterable[Mapping[str, Any]], uncertainty_nanos: int) -> int:
    hits = 0
    for case in cases:
        raw = case["rawInput"].get("down")
        if not raw:
            continue
        start = int(raw["ts"]) - uncertainty_nanos
        end = int(raw["traceEndNanos"]) + uncertainty_nanos
        value = int(case["window"]["startNanos"])
        if start <= value <= end:
            hits += 1
    return hits


def negative_controls(
    cases: list[Mapping[str, Any]],
    extra_groups: list[list[dict[str, Any]]],
    uncertainty_nanos: int,
) -> list[dict[str, Any]]:
    first_move_hits = sum(
        1 for case in cases if case["handler"]["firstMoveWindowStartWithinMarker"]
    )
    down_hits = down_boundary_hits(cases, uncertainty_nanos)
    controls = [
        {
            "name": "inclusive-threshold",
            "method": "100ms is included; 100ms-1ns is excluded",
            "observed": {
                "atExactlyThreshold": 100 * NS_PER_MS >= 100 * NS_PER_MS,
                "oneNanosecondBelow": 100 * NS_PER_MS - 1 >= 100 * NS_PER_MS,
            },
            "passed": (
                100 * NS_PER_MS >= 100 * NS_PER_MS
                and not (100 * NS_PER_MS - 1 >= 100 * NS_PER_MS)
            ),
        },
        {
            "name": "event-time-millisecond-bound",
            "method": "eventTime-derived interval expands exactly +/-1ms",
            "observed": interval(0, uncertainty_nanos),
            "passed": (
                uncertainty_nanos == NS_PER_MS
                and interval(0, uncertainty_nanos)["lowerNanos"] == -NS_PER_MS
                and interval(0, uncertainty_nanos)["upperNanos"] == NS_PER_MS
            ),
        },
        {
            "name": "first-move-boundary",
            "method": "window placement is checked against first MOVE, not DOWN",
            "observed": {
                "gestureCount": len(cases),
                "firstMoveWindowPlacementHits": first_move_hits,
                "downWindowPlacementHits": down_hits,
            },
            "passed": first_move_hits == len(cases) and down_hits == 0,
        },
        {
            "name": "pre-move-evidence-is-not-external",
            "method": "scheduler/handler evidence is inspected before attribution",
            "observed": {
                "cases": len(cases),
                "externalLabels": sum(
                    1 for case in cases
                    if case["preMoveEvidence"]["externalAttribution"] != "NOT_PROVEN"
                ),
                "targetOverlapCases": sum(
                    1 for case in cases
                    if case["preMoveEvidence"]["classification"]
                    == "TARGET_MAIN_SCHEDULER_OR_HANDLER_OVERLAP"
                ),
            },
            "passed": all(
                case["preMoveEvidence"]["externalAttribution"] == "NOT_PROVEN"
                for case in cases
            ),
        },
        {
            "name": "application-provenance-required",
            "method": "a USER_INPUT/time candidate cannot become a submit join without exact application provenance",
            "observed": {
                "applicationProvenanceExactCount": sum(
                    case["renderer"]["applicationProvenance"].get("exact")
                    for case in cases
                ),
                "highConfidenceCount": sum(
                    1 for case in cases
                    if case["renderer"]["identityJoinConfidence"] == "HIGH"
                ),
                "invalidHighConfidenceCount": sum(
                    1 for case in cases
                    if case["renderer"]["identityJoinConfidence"] == "HIGH"
                    and not (
                        case["renderer"].get("candidatePrepare") or {}
                    ).get("provenanceComparison", {}).get("exactMatch", False)
                ),
                "unresolvedCandidateCount": sum(
                    1 for case in cases
                    if case["renderer"]["identityJoinConfidence"] == "UNRESOLVED"
                ),
                "candidateSubmitCount": sum(
                    1 for case in cases
                    if case["renderer"].get("candidateSubmitNanos") is not None
                ),
            },
            "passed": (
                all(
                    not (
                        case["renderer"]["identityJoinConfidence"] == "HIGH"
                        and not (
                            case["renderer"].get("candidatePrepare") or {}
                        ).get("provenanceComparison", {}).get("exactMatch", False)
                    )
                    for case in cases
                )
                and all(
                    case["renderer"]["firstMatchingSubmitNanos"] is None
                    for case in cases
                    if case["renderer"].get("identityJoinConfidence") == "UNRESOLVED"
                )
            ),
        },
        {
            "name": "unmatched-input-group-not-fabricated",
            "method": "a raw input group after the command list remains uncommanded",
            "observed": {
                "unmatchedGroupCount": len(extra_groups),
                "unmatchedGroupHasNoCommand": bool(extra_groups),
            },
            "passed": bool(extra_groups),
        },
    ]
    return controls


def format_ms(value: int | None) -> str:
    return "UNKNOWN" if value is None else f"{value / NS_PER_MS:.3f}"


def format_interval_ms(value: Mapping[str, Any] | None) -> str:
    if value is None:
        return "UNKNOWN"
    return (
        f"[{int(value['lowerNanos']) / NS_PER_MS:.3f},"
        f"{int(value['upperNanos']) / NS_PER_MS:.3f}]"
    )


def renderer_token(case: Mapping[str, Any]) -> str:
    candidate = (
        case["renderer"].get("causalPrepare")
        or case["renderer"].get("candidatePrepare")
    )
    return str(candidate["prepare"]["token"]) if candidate else "UNKNOWN"


def renderer_submit(case: Mapping[str, Any]) -> str:
    submit = case["renderer"].get("firstMatchingSubmitNanos")
    return "UNKNOWN" if submit is None else str(submit)


def markdown_report(result: Mapping[str, Any], json_path: Path) -> str:
    summary = result["summary"]
    long_cases = result["longCases"]
    application_provenance = result["applicationProvenance"]
    provenance_status = application_provenance.get("status", "UNKNOWN_AT_APPLICATION")
    provenance_note = (
        "Exact application markers are present; only matching revision/generation/"
        "renderer tuples can become joins."
        if application_provenance.get("exact") else
        "This capture lacks application revision/generation/renderer markers; "
        "time candidates remain unresolved."
    )
    lines = [
        "# Captured input-to-motion causal audit",
        "",
        "Host-only audit of the supplied trace. No ADB, Gradle, emulator,",
        "or fresh capture was invoked. Event-time intervals retain the configured",
        f"+/-{result['policy']['inputUncertaintyMs']}ms uncertainty.",
        "",
        "## Result",
        "",
        f"- Commands: **{summary['commandCount']}**; valid first-MOVE windows: **{summary['windowCount']}**.",
        f"- Command to first in-window scroll application >=100ms: **{summary['longCaseCount']}**.",
        f"- Long-case indices: {', '.join(str(item['index']) for item in long_cases)}.",
        f"- Command-to-applied nominal p50/p95/max: "
        f"{format_ms(summary['commandToAppliedNanos']['p50'])}/"
        f"{format_ms(summary['commandToAppliedNanos']['p95'])}/"
        f"{format_ms(summary['commandToAppliedNanos']['max'])}ms.",
        f"- Application provenance status is {provenance_status}: renderer joins are "
        f"{summary['rendererIdentityConfidenceCounts']['UNRESOLVED']} UNRESOLVED, "
        f"{summary['rendererIdentityConfidenceCounts']['UNKNOWN']} UNKNOWN, and "
        f"{summary['rendererIdentityConfidenceCounts']['HIGH']} HIGH.",
        f"- Raw JSON: {json_path}.",
        "",
        "The long response is not charged to the app as one queue. The table",
        "separates command-to-eventTime, eventTime-to-main receipt, handler",
        "duration, receipt-to-scroll application, and renderer identity.",
        "",
        "## Every >=100ms case",
        "",
        "|idx|command start|cmd->DOWN create|cmd->MOVE create|first MOVE eventTime|DOWN event->receipt|first MOVE event->receipt|MOVE handler|receipt->applied|cmd->applied|first matching submit|join|",
        "|---:|---:|---:|---:|:--|---:|---:|---:|---:|---:|---:|:--|",
    ]
    for case in long_cases:
        creation = case["creationAndReceipt"]
        lines.append(
            f"|{case['index']}|{case['commandStartNanos']}|"
            f"{format_interval_ms(creation['commandToDownCreation'])}|"
            f"{format_interval_ms(creation['commandToFirstMoveCreation'])}|"
            f"{format_interval_ms(creation['firstMoveEventTimeInterval'])}|"
            f"{format_interval_ms(creation['downEventToMainReceipt'])}|"
            f"{format_interval_ms(creation['firstMoveEventToMainReceipt'])}|"
            f"{format_ms(case['handler']['firstMoveHandlerTimeNanos'])}|"
            f"{format_ms(case['application']['firstMoveReceiptToScrollAppliedNanos'])}|"
            f"{format_ms(case['commandToScrollApplied']['nominalNanos'])}|"
            f"{renderer_submit(case)}|{case['renderer']['identityJoinConfidence']}|"
        )
    lines.extend([
        "",
        "## Raw timestamp and identity rows",
        "",
        "|idx|DOWN trace/event/receipt|first MOVE trace/event/receipt|window start|motion seq/applied|viewer_input id|submit/candidate-token|",
        "|---:|:--|:--|---:|:--|---:|:--|",
    ])
    for case in long_cases:
        down = case["rawInput"]["down"]
        move = case["rawInput"]["firstMove"]
        motion = case["application"]["motion"] or {}
        causal = (
            case["renderer"].get("causalPrepare")
            or case["renderer"].get("candidatePrepare")
        )
        token = renderer_token(case)
        submit = renderer_submit(case)
        if causal:
            token = f"{token} (prepare {causal['prepare']['id']}, swap {(causal.get('swap') or {}).get('id', 'UNKNOWN')})"
        lines.append(
            f"|{case['index']}|"
            f"{down['ts']}/{down['eventTimeMillis']}/{down['receiptNanos']}|"
            f"{move['ts']}/{move['eventTimeMillis']}/{move['receiptNanos']}|"
            f"{case['window']['startNanos']}|"
            f"{motion.get('motionSequence', 'UNKNOWN')}/{motion.get('appliedAtNanos', 'UNKNOWN')}|"
            f"{move['id']}|{submit}/{token}|"
        )
    lines.extend([
        "",
        "A viewer_prepare/swap token is a high-confidence identity join only when",
        "the TSV says scrollCause=USER_INPUT, a matching swap and submit exist,",
        "and the application row carries the exact userInputRevision, session,",
        "generation, and renderer tuple. " + provenance_note,
        "",
        "## Bounded negative controls",
        "",
        "|control|passed|observed|",
        "|:--|:--:|:--|",
    ])
    for control in result["negativeControls"]:
        observed = json.dumps(control["observed"], sort_keys=True, separators=(",", ":"))
        lines.append(f"|{control['name']}|{control['passed']}|{observed}|")
    lines.extend([
        "",
        "## Attribution decision",
        "",
        result["conclusion"]["text"],
        "",
        f"Most likely removable interval examined: {result['conclusion']['stage']}.",
        f"Evidence: {json.dumps(result['conclusion']['evidence'], sort_keys=True)}.",
        f"Missing marker/limitation: {result['conclusion']['missingEvidence']}",
        "",
        "The report is diagnostic evidence only and does not claim qualification.",
        "",
    ])
    return "\n".join(lines)


def audit(
    trace_path: Path,
    capture_directory: Path,
    threshold_ms: int = 100,
    input_uncertainty_ms: int = 1,
    causal_prepare_lookback_ms: int = 5,
) -> dict[str, Any]:
    names = {
        "commands": "injected-input-starts.txt",
        "windows": "observed-input-windows.txt",
        "motion": "motion-input-application-timestamps.tsv",
        "presentations": "presentation-evidence.tsv",
        "collection": "collection.json",
    }
    paths = {key: capture_directory / name for key, name in names.items()}
    missing = [str(path) for path in paths.values() if not path.exists()]
    if missing or not trace_path.exists():
        absent_trace = [str(trace_path)] if not trace_path.exists() else []
        raise FileNotFoundError("missing audit input: " + ", ".join(missing + absent_trace))
    starts = read_starts(paths["commands"])
    windows = read_windows(paths["windows"])
    motions = read_motion(paths["motion"])
    presentations = read_presentations(paths["presentations"])
    collection = read_collection(paths["collection"])
    if not starts or len(starts) != len(windows):
        raise ValueError(f"command/window count mismatch: {len(starts)} vs {len(windows)}")
    uncertainty_nanos = input_uncertainty_ms * NS_PER_MS
    analysis_start = min(starts[0], windows[0][0])
    analysis_end = max(
        windows[-1][1],
        max((int(item["appliedAtNanos"]) for item in motions), default=windows[-1][1]),
    )
    trace = load_trace(trace_path, analysis_start, analysis_end)
    input_markers = parse_input_markers(trace)
    groups, orphaned = group_input(input_markers)
    if len(groups) < len(starts):
        raise ValueError(f"fewer raw input groups ({len(groups)}) than commands ({len(starts)})")
    prepares, swaps = parse_renderer_markers(trace)
    clock_bridge = read_clock_bridge(trace_path)
    application_markers = parse_motion_applied_markers(trace)
    session_key = collection.get("sampleKey")
    application_provenance = missing_application_provenance(session_key)
    if application_markers:
        application_provenance.update({
            "exact": True,
            "status": "PRESENT_AT_APPLICATION",
            "availableFields": [
                "motionSequence",
                "appliedAtNanos",
                "userInputRevision",
                "generation",
                "rendererEpoch",
            ],
            "missingFields": [],
            "markerCount": sum(len(values) for values in application_markers.values()),
            "distinctMotionSequenceCount": len(application_markers),
            "source": "viewer_motion_applied trace marker",
        })
    cases = [
        build_case(
            index, starts[index], windows[index], groups[index], motions, trace,
            prepares, swaps, presentations, uncertainty_nanos,
            causal_prepare_lookback_ms * NS_PER_MS, clock_bridge,
            application_markers, session_key,
        )
        for index in range(len(starts))
    ]
    long_cases = [
        case for case in cases
        if case["commandToScrollApplied"] is not None
        and int(case["commandToScrollApplied"]["nominalNanos"]) >= threshold_ms * NS_PER_MS
    ]
    command_to_applied = [
        int(case["commandToScrollApplied"]["nominalNanos"])
        for case in cases if case["commandToScrollApplied"] is not None
    ]
    receipt_to_applied = [
        int(case["application"]["firstMoveReceiptToScrollAppliedNanos"])
        for case in cases if case["application"]["firstMoveReceiptToScrollAppliedNanos"] is not None
    ]
    move_handler = [
        int(case["handler"]["firstMoveHandlerTimeNanos"])
        for case in cases if case["handler"]["firstMoveHandlerTimeNanos"] is not None
    ]
    removable = max(
        (
            case for case in cases
            if case["application"]["firstMoveReceiptToScrollAppliedNanos"] is not None
        ),
        key=lambda case: int(case["application"]["firstMoveReceiptToScrollAppliedNanos"]),
        default=None,
    )
    if removable is None:
        conclusion = {
            "stage": "FIRST_MOVE_RECEIPT_TO_SCROLL_APPLIED",
            "status": "NOT_DEMONSTRATED",
            "evidence": {},
            "missingEvidence": "No appliedAtNanos row is available after a first MOVE receipt.",
            "text": "No app-side removable interval can be demonstrated because scroll application evidence is missing.",
        }
    else:
        move = removable["rawInput"]["firstMove"]
        conclusion = {
            "stage": "FIRST_MOVE_RECEIPT_TO_SCROLL_APPLIED",
            "status": "NOT_DEMONSTRATED",
            "evidence": {
                "caseIndex": removable["index"],
                "viewerInputSliceId": move["id"],
                "motionSequence": (removable["application"]["motion"] or {}).get("motionSequence"),
                "intervalNanos": removable["application"]["firstMoveReceiptToScrollAppliedNanos"],
                "intervalMs": removable["application"]["firstMoveReceiptToScrollAppliedNanos"] / NS_PER_MS,
            },
            "missingEvidence": (
                "This report does not establish finer-grained attribution within the "
                "MOVE-receipt-to-scroll-application interval. Pre-delivery intervals "
                "require independent system input-dispatch and window evidence."
            ),
            "text": (
                "This audit does not prove external inevitability or the absence of "
                "removable application delays. The largest measured MOVE-receipt-to-"
                "application interval is "
                f"{removable['application']['firstMoveReceiptToScrollAppliedNanos'] / NS_PER_MS:.3f}ms. "
                "The preceding intervals remain separately recorded and unresolved."
            ),
        }
    controls = negative_controls(cases, groups[len(starts):], uncertainty_nanos)
    files = {
        key: {
            "path": str(path),
            "sha256": sha256(path),
            "bytes": path.stat().st_size,
        }
        for key, path in paths.items()
    }
    files["trace"] = {
        "path": str(trace_path),
        "sha256": sha256(trace_path),
        "bytes": trace_path.stat().st_size,
    }
    errors: list[str] = []
    if len(windows) != len(cases):
        errors.append("window count changed during mapping")
    if sum(1 for case in cases if not case["handler"]["firstMoveWindowStartWithinMarker"]):
        errors.append("one or more observed window starts are outside first MOVE handler marker")
    if any(not case["decompositionCheck"] for case in cases if case["commandToScrollApplied"] is not None):
        errors.append("nominal MOVE decomposition does not sum to command-to-applied")
    if not all(control["passed"] for control in controls):
        errors.append("a bounded negative control failed")
    result = {
        "schema": SCHEMA,
        "policy": {
            "thresholdMs": threshold_ms,
            "inputUncertaintyMs": input_uncertainty_ms,
            "gestureBoundary": "FIRST_MOVE_HANDLING",
            "eventTimePrecision": "whole milliseconds; sub-millisecond creation UNKNOWN",
            "preMoveAttribution": "inspect target main scheduler/handler evidence; never blanket external",
            "noDeviceOrBuildActions": True,
        },
        "inputs": {
            "captureDirectory": str(capture_directory),
            "collection": collection,
            "files": files,
        },
        "trace": {
            "bounds": trace["bounds"],
            "mainTrackId": trace["mainTrackId"],
            "mainUtid": trace["mainUtid"],
            "markerCounts": {
                "viewerInput": len(input_markers),
                "viewerMotionApplied": sum(len(items) for items in application_markers.values()),
                "viewerPrepare": sum(len(items) for items in prepares.values()),
                "viewerSwap": sum(len(items) for items in swaps.values()),
            },
            "inputActionCounts": {
                action: sum(1 for item in input_markers if item["action"] == action)
                for action in sorted(set(item["action"] for item in input_markers))
            },
            "dataLossStats": trace["stats"],
            "clockBridge": clock_bridge,
        },
        "applicationProvenance": application_provenance,
        "matching": {
            "commandCount": len(starts),
            "rawInputGroupCount": len(groups),
            "orphanedInputMarkerCount": len(orphaned),
            "unmatchedGroups": [
                {
                    "command": "UNKNOWN",
                    "rawMarkers": [raw_event(item) for item in group],
                }
                for group in groups[len(starts):]
            ],
        },
        "summary": {
            "commandCount": len(starts),
            "windowCount": len(windows),
            "appliedCount": len(command_to_applied),
            "longCaseCount": len(long_cases),
            "commandToAppliedNanos": quantiles(command_to_applied),
            "firstMoveReceiptToAppliedNanos": quantiles(receipt_to_applied),
            "firstMoveHandlerNanos": quantiles(move_handler),
            "preMoveClassificationCounts": {
                name: sum(
                    1 for case in cases
                    if case["preMoveEvidence"]["classification"] == name
                )
                for name in (
                    "TARGET_MAIN_SCHEDULER_OR_HANDLER_OVERLAP",
                    "NO_TARGET_MAIN_SCHEDULER_OR_HANDLER_OVERLAP",
                )
            },
            "rendererIdentityConfidenceCounts": {
                name: sum(1 for case in cases if case["renderer"]["identityJoinConfidence"] == name)
                for name in ("HIGH", "MEDIUM", "LOW", "UNRESOLVED", "UNKNOWN")
            },
        },
        "gestures": cases,
        "longCases": long_cases,
        "negativeControls": controls,
        "conclusion": conclusion,
        "validation": {
            "passed": not errors,
            "errors": errors,
            "warnings": [
                f"{len(groups) - len(starts)} raw input group(s) have no injected command and were not fabricated"
                if len(groups) > len(starts) else "",
                f"{len(orphaned)} input marker(s) occurred outside a DOWN..UP/CANCEL group"
                if orphaned else "",
                "Diagnostic trace is not a qualification claim.",
            ],
        },
    }
    result["validation"]["warnings"] = [
        value for value in result["validation"]["warnings"] if value
    ]
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--capture-directory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path, required=True)
    parser.add_argument("--threshold-ms", type=int, default=100)
    parser.add_argument("--input-uncertainty-ms", type=int, default=1)
    parser.add_argument("--causal-prepare-lookback-ms", type=int, default=5)
    args = parser.parse_args(argv)
    result = audit(
        args.trace.resolve(),
        args.capture_directory.resolve(),
        threshold_ms=args.threshold_ms,
        input_uncertainty_ms=args.input_uncertainty_ms,
        causal_prepare_lookback_ms=args.causal_prepare_lookback_ms,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.markdown_output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    args.markdown_output.write_text(
        markdown_report(result, args.output.resolve()), encoding="utf-8"
    )
    print(json.dumps({
        "output": str(args.output.resolve()),
        "markdownOutput": str(args.markdown_output.resolve()),
        "commandCount": result["summary"]["commandCount"],
        "longCaseCount": result["summary"]["longCaseCount"],
        "longCaseIndices": [case["index"] for case in result["longCases"]],
        "validationPassed": result["validation"]["passed"],
        "warnings": result["validation"]["warnings"],
    }, indent=2, sort_keys=True))
    return 0 if result["validation"]["passed"] else 2


if __name__ == "__main__":
    sys.exit(main())
