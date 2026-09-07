#!/usr/bin/env python3
"""Diagnostic RGB observation bounds for the emulator's known-source scroll probe.

The gRPC timestamp is estimated generation time, not a hardware present time.
Only independently received RGB bytes establish an observation upper bound. This
tool verifies their hashes and order, compares the actual source image, preserves
visually indistinguishable native-frame candidates, and relates unique matches to
FrameTracer events. It does not turn an experiment into a universal calibration.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import sys
from typing import Any

import numpy as np
from PIL import Image

from verify_display_trace import PACKAGE, SWAP, Findings, TraceIndex, json_safe, read_json, sha256

MAX_PROBE_FRAMES = 10_000
MAX_CAPTURE_FRAMES = 20_000
MAX_CANDIDATE_COMPARISONS = 5_000_000
MAX_RGB_MAE = 4.0  # Fixed comparison tolerance in 8-bit RGB units; not a timing tolerance.
PROBE_LAYER = "OwnedRendererProbeActivity](BLAST)"


def intersect(intervals: list[tuple[int, int]]) -> tuple[int, int] | None:
    if not intervals:
        return None
    lower = max(item[0] for item in intervals)
    upper = min(item[1] for item in intervals)
    return (lower, upper) if lower <= upper else None


def clock_mapping(probe: dict[str, Any], host_guest: Any | None) -> dict[str, Any]:
    pairs = probe.get("clockPairs", [])
    guest = []
    for pair in pairs:
        before, after, epoch = (int(pair[key]) for key in ("nativeBeforeNanos", "nativeAfterNanos", "epochMillis"))
        if before <= 0 or after < before or epoch <= 0:
            raise ValueError("Invalid native/guest-epoch clock bracket")
        # currentTimeMillis is quantized to a millisecond, including its full cell.
        guest.append((epoch * 1_000_000 - after, (epoch + 1) * 1_000_000 - before))
    guest_interval = intersect(guest)
    host = []
    samples = host_guest if isinstance(host_guest, list) else (host_guest or {}).get("samples", [])
    for sample in samples:
        before, epoch, after = (int(sample[key]) for key in ("hostBeforeEpochNanos", "guestEpochNanos", "hostAfterEpochNanos"))
        if before <= 0 or after < before or epoch <= 0:
            raise ValueError("Invalid host/guest clock roundtrip")
        host.append((before - epoch, after - epoch))
    host_interval = intersect(host)
    mapped = None
    if guest_interval is not None and host_interval is not None:
        mapped = (guest_interval[0] + host_interval[0], guest_interval[1] + host_interval[1])
    return {"guestEpochMinusNativeNanos": guest_interval, "hostEpochMinusGuestEpochNanos": host_interval,
            "hostEpochMinusNativeNanos": mapped, "independentlyBracketed": mapped is not None,
            "assumption": "Clock offsets remain within the intersecting contemporaneous measurement brackets; no estimated gRPC timestamp is used"}


def bounded_jsonl(path: Path, maximum: int) -> list[dict[str, Any]]:
    result = []
    with path.open(encoding="utf-8-sig") as stream:
        for line, text in enumerate(stream, 1):
            if line > maximum:
                raise ValueError(f"Diagnostic capture exceeds bounded analysis capacity: {path.name}")
            result.append(json.loads(text))
    return result


def source_templates(source: np.ndarray, probe: dict[str, Any], frames: list[dict[str, Any]],
                     capture_width: int, capture_height: int) -> tuple[np.ndarray, np.ndarray, tuple[int, int]]:
    """Model the probe's full-width repeating page; avoid platform/system-bar pixels."""
    bounds = probe["surfaceBounds"]
    display_width, display_height = int(probe["displayWidthPx"]), int(probe["displayHeightPx"])
    left, top, right, bottom = (int(bounds[key]) for key in ("left", "top", "right", "bottom"))
    if not (0 <= left < right <= display_width and 0 <= top < bottom <= display_height):
        raise ValueError("Invalid probe surface bounds")
    if source.shape[:2] != (int(probe["sourceHeightPx"]), int(probe["sourceWidthPx"])):
        raise ValueError("Source image dimensions disagree with probe metadata")
    # The calibration probe renders at its source width. No scaling guess is made.
    if right - left != source.shape[1]:
        raise ValueError("Probe surface/source width mismatch requires an explicit rendering model")
    band = source[:, source.shape[1] // 4:3 * source.shape[1] // 4].astype(np.float32)
    profile = np.median(band, axis=1)
    if np.max(np.abs(band - profile[:, None, :])) > 4:
        raise ValueError("Reference is not the horizontally uniform known probe pattern")
    row_centers = (np.arange(capture_height) + 0.5) * display_height / capture_height
    selected = np.flatnonzero((row_centers > top + 2 * display_height / capture_height)
                              & (row_centers < bottom - 2 * display_height / capture_height))
    x_start = math.ceil((left + (right - left) * 0.25) * capture_width / display_width)
    x_end = math.floor((left + (right - left) * 0.75) * capture_width / display_width)
    if len(selected) < 20 or x_end <= x_start:
        raise ValueError("Capture resolution cannot resolve the calibration pattern")
    templates = []
    texture_height = source.shape[0]
    for frame in frames:
        offset = float(frame["scrollOffsetPx"])
        if not math.isfinite(offset) or offset < 0:
            raise ValueError("Invalid native scroll offset")
        # A GPU GL_LINEAR resize samples two source texels at the mapped pixel
        # center. Pillow's antialiased downscale widens its filter and is NOT this
        # rendering operation. Keep the fixed source-to-capture sample geometry.
        source_y = ((selected + 0.5) * display_height / capture_height - 0.5 - top + offset) % texture_height
        lower = np.floor(source_y).astype(np.int64)
        fraction = (source_y - lower)[:, None]
        pixels = profile[lower] * (1 - fraction) + profile[(lower + 1) % texture_height] * fraction
        templates.append(pixels)
    return np.asarray(templates, dtype=np.float32), selected, (x_start, x_end)


def read_rgb(directory: Path, observation: dict[str, Any], pixel_order: str | None = None) -> np.ndarray:
    filename = observation["file"]
    if not isinstance(filename, str) or Path(filename).name != filename:
        raise ValueError("Capture image filename must stay inside the capture directory")
    path = directory / filename
    if sha256(path) != observation["sha256"]:
        raise ValueError(f"Capture RGB hash mismatch: {filename}")
    width, height = int(observation["width"]), int(observation["height"])
    if width <= 0 or height <= 0:
        raise ValueError("Invalid capture dimensions")
    pixels = np.fromfile(path, dtype=np.uint8)
    if pixels.size != width * height * 3:
        raise ValueError("Truncated capture RGB bytes")
    order = pixel_order or observation.get("pixelOrder")
    if order not in ("left-to-right,bottom-up", "left-to-right,top-down"):
        raise ValueError("Unrecognized capture RGB orientation")
    image = pixels.reshape((height, width, 3))
    return image[::-1] if order == "left-to-right,bottom-up" else image


def monotone_candidates(candidates: list[list[int]]) -> list[list[int]]:
    """Keep all nondecreasing scene paths; repeated identical scenes remain ambiguous."""
    retained = [list(items) for items in candidates]
    lower = 0
    for index, items in enumerate(retained):
        if items:
            retained[index] = [item for item in items if item >= lower]
            if retained[index]:
                lower = retained[index][0]
    upper = math.inf
    for index in range(len(retained) - 1, -1, -1):
        if retained[index]:
            retained[index] = [item for item in retained[index] if item <= upper]
            if retained[index]:
                upper = retained[index][-1]
    return retained


def trace_probe_frames(trace: Path, frames: list[dict[str, Any]], findings: Findings,
                       process_pid: int | None = None) -> tuple[dict[int, dict[str, Any]], tuple[int, int] | None]:
    from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig

    markers: dict[tuple[int, int], list[dict[str, Any]]] = {}
    events: dict[tuple[str, int, str], list[int]] = {}
    unverified_processes = set()
    with TraceProcessor(trace=str(trace), config=TraceProcessorConfig(load_timeout=120)) as processor:
        for row in processor.query("SELECT name,severity,value FROM stats WHERE severity='data_loss' AND value != 0"):
            findings.add("trace", "Trace data loss", **vars(row))

        def marker(record: dict[str, Any]) -> None:
            parsed = SWAP.fullmatch(record["name"])
            if parsed is None or record["dur"] < 0:
                findings.add("trace", "Invalid probe swap marker")
                return
            if (record.get("parent_name") != "viewer_clock" or record.get("parent_track_id") != record.get("track_id")
                    or record.get("track_id") is None
                    or record.get("parent_ts") is None or record.get("parent_dur") is None or record["parent_dur"] < 0
                    or record["parent_ts"] > record["ts"]
                    or record["parent_ts"] + record["parent_dur"] < record["ts"] + record["dur"]):
                findings.add("trace", "Probe swap lacks a complete directly nested viewer_clock bracket")
                return
            ownership = process_pid is not None and record["pid"] == process_pid
            if not ownership and record["pid"] not in unverified_processes:
                unverified_processes.add(record["pid"])
                findings.add("ownership", "Trace process ownership is unverified; raw correlation is diagnostic only",
                             tracePid=record["pid"], traceProcessName=record["process_name"], probePid=process_pid)
            token, frame, native = map(int, parsed.groups())
            markers.setdefault((token, frame), []).append({**record, "native": native, "ownershipConfirmed": ownership})

        TraceIndex._paged(processor, "SELECT s.id,s.name,s.ts,s.dur,s.track_id,p.pid,p.name AS process_name,"
                          "clock.ts AS parent_ts,clock.dur AS parent_dur,clock.name AS parent_name,clock.track_id AS parent_track_id FROM slice s "
                          "JOIN thread_track tt ON tt.id=s.track_id JOIN thread t ON t.utid=tt.utid "
                          "JOIN process p ON p.upid=t.upid LEFT JOIN slice clock ON clock.id=s.parent_id "
                          "WHERE s.name GLOB 'viewer_swap:*' AND s.id>{after} "
                          "ORDER BY s.id LIMIT {limit}", marker)

        def event(record: dict[str, Any]) -> None:
            events.setdefault((record["layer_name"], record["frame_number"], record["name"]), []).append(record["ts"])

        TraceIndex._paged(processor, "SELECT id,ts,name,layer_name,frame_number FROM frame_slice WHERE "
                          "instr(layer_name,'OwnedRendererProbeActivity](BLAST)')>0 AND "
                          "name IN ('Queue','PresentFenceSignaled') AND id>{after} ORDER BY id LIMIT {limit}", event)
    layers = {key[0] for key in events}
    mapped = {}
    offsets = []
    for index, frame in enumerate(frames):
        token, buffer_id = int(frame["token"]), int(frame["bufferFrameId"])
        low, high = int(frame["submittedAtNanos"]), int(frame["submittedAtNanos"]) + int(frame["renderLatencyNanos"])
        possible = [item for item in markers.get((token, buffer_id), []) if low <= item["native"] <= high]
        if len(possible) != 1:
            findings.add("trace", "Missing or ambiguous native probe marker", token=token)
            continue
        bridge = possible[0]
        possible_queues = [(layer, timestamp) for layer in layers for timestamp in events.get((layer, buffer_id, "Queue"), [])
                           if bridge["ts"] <= timestamp <= bridge["ts"] + bridge["dur"] + 1_000_000_000]
        if len(possible_queues) != 1:
            findings.add("trace", "Missing or ambiguous probe Queue", token=token)
            continue
        layer, queue = possible_queues[0]
        present = events.get((layer, buffer_id, "PresentFenceSignaled"), [])
        if len(present) != 1 or present[0] < queue:
            findings.add("trace", "Missing or noncausal FrameTracer present event", token=token)
            continue
        offsets.append((bridge["parent_ts"] - bridge["native"], bridge["ts"] - bridge["native"]))
        mapped[index] = {"layer": layer, "frameNumber": buffer_id, "queueTraceNanos": queue,
                         "presentFenceSignaledTraceNanos": present[0], "origin": "PRESENT_FENCE_OR_HWC_VSYNC_FALLBACK",
                         "ownershipConfirmed": bridge["ownershipConfirmed"], "tracePid": bridge["pid"],
                         "traceProcessName": bridge["process_name"]}
    interval = intersect(offsets)
    if offsets and interval is None:
        findings.add("trace", "Nested viewer_clock brackets have no common native/trace offset")
    return mapped, interval


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def analyze(capture_directory: Path, probe_directory: Path, trace: Path | None,
            host_guest_path: Path | None, pixel_order: str | None = None) -> dict[str, Any]:
    findings = Findings()
    probe_path, frame_path = probe_directory / "probe.json", probe_directory / "frames.jsonl"
    source_path = probe_directory / "source.jpg"
    probe = read_json(probe_path)
    frames = bounded_jsonl(frame_path, MAX_PROBE_FRAMES)
    frames.sort(key=lambda frame: (int(frame["submittedAtNanos"]), int(frame["token"])))
    if not frames or len({(frame["token"], frame["bufferFrameId"]) for frame in frames}) != len(frames):
        raise ValueError("Missing or duplicated native probe frames")
    observations_path = capture_directory / "observations.jsonl"
    observations = bounded_jsonl(observations_path, MAX_CAPTURE_FRAMES)
    if not observations:
        raise ValueError("No independently received RGB observations")
    if len(frames) * len(observations) > MAX_CANDIDATE_COMPARISONS:
        raise ValueError("Calibration capture exceeds bounded candidate-matching capacity")
    clocks = clock_mapping(probe, read_json(host_guest_path) if host_guest_path else None)
    mapping = clocks["hostEpochMinusNativeNanos"]
    summary = read_json(capture_directory / "summary.json")
    if summary.get("frames") != len(observations):
        findings.add("capture", "Capture summary frame count disagrees with raw observations")
    source = np.asarray(Image.open(source_path).convert("RGB"))
    width, height = int(observations[0]["width"]), int(observations[0]["height"])
    templates, rows, columns = source_templates(source, probe, frames, width, height)
    candidates = []
    errors = []
    previous_sequence = -1
    previous_observed = 0
    dropped = 0
    for observation in observations:
        sequence, received = int(observation["sequence"]), int(observation["observedByEpochNanos"])
        if sequence <= previous_sequence or received < previous_observed:
            findings.add("capture", "Nonmonotonic screenshot sequence or host receipt clock")
        dropped += max(0, sequence - previous_sequence - 1)
        previous_sequence, previous_observed = sequence, received
        if (int(observation["width"]), int(observation["height"])) != (width, height):
            raise ValueError("Capture resolution changed within the calibration stream")
        image = read_rgb(capture_directory, observation, pixel_order)
        profile = np.median(image[rows, columns[0]:columns[1]], axis=1).astype(np.float32)
        distance = np.mean(np.abs(templates - profile[None, :, :]), axis=(1, 2))
        choices = np.flatnonzero(distance <= MAX_RGB_MAE).tolist()
        if mapping is not None:
            choices = [choice for choice in choices if int(frames[choice]["submittedAtNanos"]) + mapping[0] <= received]
        candidates.append(choices)
        errors.append(float(distance.min()))
    if dropped != summary.get("missingSequences"):
        findings.add("capture", "Capture summary sequence gap count disagrees with raw observations")
    if dropped:
        findings.add("capture", "Screenshot stream dropped observations", count=dropped)
    retained = monotone_candidates(candidates)
    process_pid = probe.get("processPid") if probe.get("packageName") == PACKAGE else None
    traced, trace_offsets = trace_probe_frames(trace, frames, findings, process_pid) if trace else ({}, None)
    records = []
    unique_native = set()
    first_content_index = next((i for i, choices in enumerate(retained) if choices), None)
    last_content_index = next((i for i in range(len(retained) - 1, -1, -1) if retained[i]), None)
    submit_upper: dict[int, float] = {}
    present_to_observed_upper: dict[int, float] = {}
    ambiguous = unmatched_inside = 0
    for index, (observation, choices) in enumerate(zip(observations, retained)):
        inside = first_content_index is not None and first_content_index <= index <= last_content_index
        if inside and not choices:
            unmatched_inside += 1
        ambiguous += int(len(choices) > 1)
        record = {"sequence": observation["sequence"], "file": observation["file"], "sha256": observation["sha256"],
                  "observedByEpochNanos": observation["observedByEpochNanos"],
                  "estimatedGenerationEpochMicros": observation["estimatedGenerationEpochMicros"],
                  "estimatedTimestampUsedAsProof": False, "minimumMeanAbsoluteRGBError": errors[index],
                  "candidateCount": len(choices), "candidateTokens": [frames[item]["token"] for item in choices[:100]],
                  "insideObservedProbeInterval": inside}
        if len(choices) == 1:
            choice = choices[0]
            native = frames[choice]
            unique_native.add(choice)
            record.update(token=native["token"], bufferFrameId=native["bufferFrameId"],
                          scrollOffsetPx=native["scrollOffsetPx"], sceneRevision=native["sceneRevision"])
            if mapping is not None:
                receipt = int(observation["observedByEpochNanos"])
                upper = (receipt - int(native["submittedAtNanos"]) - mapping[0]) / 1_000_000
                submit_upper[choice] = min(submit_upper.get(choice, math.inf), upper)
                record["submitToObservedUpperBoundMillis"] = upper
                record["observedSceneIntervalEpochNanos"] = [int(native["submittedAtNanos"]) + mapping[0], receipt]
                if choice in traced and trace_offsets is not None:
                    present = traced[choice]["presentFenceSignaledTraceNanos"]
                    lower_host = present - trace_offsets[1] + mapping[0]
                    upper_host = present - trace_offsets[0] + mapping[1]
                    interval = [(receipt - upper_host) / 1_000_000, (receipt - lower_host) / 1_000_000]
                    record["frameTracerToObservedMillisInterval"] = interval
                    record["frameTracer"] = traced[choice]
                    present_to_observed_upper[choice] = min(present_to_observed_upper.get(choice, math.inf), interval[1])
                    if interval[1] < 0:
                        findings.add("calibration", "FrameTracer present event occurs after independently received matching pixels under the clock bounds", token=native["token"])
        records.append(record)
    if unmatched_inside:
        findings.add("capture", "RGB observations inside the probe interval could not be matched without guessing", count=unmatched_inside)
    if not unique_native:
        findings.add("calibration", "No native scene was uniquely identified from independent RGB evidence")
    if mapping is None:
        findings.add("clock", "Host/guest/native clocks lack intersecting independently bracketed measurements")
    return {"schemaVersion": 1, "mode": "DIAGNOSTIC_NO_CORPUS_CREDIT", "passed": False,
            "qualifiesPhysicalPresentation": False, "requiresCalibration": True,
            "clockMapping": clocks, "traceClockOffsetNanos": trace_offsets,
            "hostNativeClockUncertaintyMillis": (mapping[1] - mapping[0]) / 1_000_000 if mapping else None,
            "captureResolution": [width, height], "comparisonToleranceMeanAbsoluteRGB": MAX_RGB_MAE,
            "pixelOrderDeclaredByCapture": observations[0].get("pixelOrder"),
            "sdkDeclaredPixelOrder": observations[0].get("sdkDeclaredPixelOrder"),
            "pixelOrderUsedForDiagnosticComparison": pixel_order or observations[0].get("pixelOrder"),
            "resamplingModel": "GL_LINEAR point sampling at output pixel centers; no antialiasing filter expansion",
            "nativeFrameCount": len(frames), "captureObservationCount": len(observations),
            "uniqueNativeFramesObserved": len(unique_native), "ambiguousCaptureObservations": ambiguous,
            "unmatchedInsideProbeObservations": unmatched_inside, "droppedScreenshotSequences": dropped,
            "nativeFramesNotUniquelyObserved": len(frames) - len(unique_native),
            "nativeFramesNotUniquelyObservedRatio": (len(frames) - len(unique_native)) / len(frames),
            "exactFrameTracerBuffers": len(traced),
            "uniquelyObservedFrameTracerBuffers": len(unique_native.intersection(traced)),
            "frameTracerBuffersWithoutUniqueRgb": len(set(traced) - unique_native),
            "uniqueRgbBuffersWithoutFrameTracer": len(unique_native - set(traced)),
            "uniqueRgbTokensWithoutFrameTracer": [frames[item]["token"] for item in sorted(unique_native - set(traced))[:100]],
            "nativeTokensWithoutUniqueRgb": [frame["token"] for item, frame in enumerate(frames) if item not in unique_native][:100],
            "timingMetricCohort": "Earliest independent RGB observation per uniquely identified native scene; all repeated observations remain in the report",
            "submitToObservedUpperBoundMillis": {"p95": percentile(list(submit_upper.values()), .95), "maximum": max(submit_upper.values(), default=None)},
            "frameTracerToObservedUpperMillis": {"p95": percentile(list(present_to_observed_upper.values()), .95), "maximum": max(present_to_observed_upper.values(), default=None)},
            "canBoundEveryNativeFrameUnder100ms": (not findings.counts and len(unique_native) == len(frames)
                                                     and bool(submit_upper) and max(submit_upper.values()) < 100),
            "limitations": ["Captured RGB confirms observed emulator output, not a hardware scanout timestamp.",
                            "Center-half RGB row profiles identify the known horizontal-stripe probe; they do not replace full-image corpus verification.",
                            "Receipt times include screenshot generation, transport, and observer scheduling.",
                            "Repeated source imagery and repeated scenes can make buffer identity ambiguous.",
                            "No finite calibration run establishes universal future bounds or a 200-episode pass."],
            "evidenceSha256": {"probeMetadata": sha256(probe_path), "nativeFrames": sha256(frame_path),
                               "analyzer": sha256(Path(__file__)),
                               "clockCorrelationVerifier": sha256(Path(__file__).with_name("verify_display_trace.py")),
                               "sourceImage": sha256(source_path), "captureObservations": sha256(observations_path),
                               "captureSummary": sha256(capture_directory / "summary.json"),
                               "trace": sha256(trace) if trace else None,
                               "hostGuestClock": sha256(host_guest_path) if host_guest_path else None},
            "observations": records, "violations": findings.items, "violationCounts": findings.counts}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture-directory", type=Path, required=True)
    parser.add_argument("--probe-directory", type=Path, required=True)
    parser.add_argument("--trace", type=Path)
    parser.add_argument("--host-guest-clock", type=Path)
    parser.add_argument("--pixel-order", choices=("left-to-right,bottom-up", "left-to-right,top-down"),
                        help="Explicit diagnostic decoder model; recorded declaration is retained, and this is not physical-time calibration")
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        result = analyze(arguments.capture_directory, arguments.probe_directory, arguments.trace, arguments.host_guest_clock, arguments.pixel_order)
    except Exception as error:
        result = {"passed": False, "qualifiesPhysicalPresentation": False, "requiresCalibration": True,
                  "violations": [{"gate": "analyzer", "reason": f"{type(error).__name__}: {error}"}]}
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(json_safe(result), ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps({key: result.get(key) for key in ("uniqueNativeFramesObserved", "nativeFramesNotUniquelyObservedRatio", "requiresCalibration")}, ensure_ascii=False))
    return 1 if "analyzer" in result.get("violationCounts", {}) or any(item["gate"] == "analyzer" for item in result["violations"]) else 0


if __name__ == "__main__":
    sys.exit(main())
