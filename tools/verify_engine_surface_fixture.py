"""Bind exact engine readback frames to SurfaceFlinger producer and layer evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import sys
from pathlib import Path
from typing import Any, Iterable

try:
    from verify_engine_readback_fixture import verify_fixture
except ImportError:  # pragma: no cover - supports ``python -m tools...``.
    from tools.verify_engine_readback_fixture import verify_fixture


PACKAGE = "ml.melun.mangaview"
TARGET_LAYER = "SurfaceView[ml.melun.mangaview/ml.melun.mangaview.viewer.runtime.EngineReadbackProbeActivity](BLAST)"
CONSUMER_BASE = TARGET_LAYER.removesuffix("(BLAST)")
FRAME_LAYER = re.compile(r"^" + re.escape(TARGET_LAYER) + r"#([0-9]+)$")
SWAP = re.compile(r"^viewer_swap:([0-9]+):([0-9]+):([0-9]+)$")
CONSUMER_NAME = re.compile(r"^" + re.escape(CONSUMER_BASE) + r"#([0-9]+)$")
ON_FRAME = re.compile(r"^onFrameAvailable - (" + re.escape(CONSUMER_BASE) + r"#[0-9]+)\(f:[0-9]+,a:[0-9]+\)$")
ACQUIRE = re.compile(r"^acquireNextBufferLocked - (.+)\(f:[0-9]+,a:[0-9]+\)frame=([0-9]+)$")
ALLOWED_PARENT = re.compile(r"^(?:queueBuffer|onFrameAvailable - .+|releaseBufferCallback(?:Locked)?(?: - .+)?)$")
SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
HEADER = struct.Struct("<16q")
MAGIC = 0x4552474253545250
TRACE_BATCH = 4096


class SurfaceFixtureError(ValueError):
    pass


def _fail(reason: str) -> None:
    raise SurfaceFixtureError(reason)


def _require(condition: bool, reason: str) -> None:
    if not condition:
        _fail(reason)


def _int(value: Any, label: str) -> int:
    _require(isinstance(value, int) and not isinstance(value, bool), f"{label} is not an integer")
    return value


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
    except OSError as error:
        _fail(f"cannot hash {path}: {error}")
    return digest.hexdigest()


def _hash(value: Any, label: str) -> str:
    _require(isinstance(value, str) and SHA256.fullmatch(value) is not None,
             f"{label} is not a SHA-256 hex string")
    return value.lower()


def _get(row: dict[str, Any], *names: str) -> Any:
    for name in names:
        if name in row:
            return row[name]
    return None


def _slice_end(row: dict[str, Any]) -> int:
    start = _int(_get(row, "ts", "start_ts"), "slice timestamp")
    duration = _int(_get(row, "dur", "duration"), "slice duration")
    _require(duration >= 0, "slice duration is negative")
    return start + duration


def _descendants(rows_by_id: dict[int, dict[str, Any]], children: dict[int, list[int]], root: int) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    pending = list(children.get(root, []))
    seen: set[int] = set()
    while pending:
        identifier = pending.pop(0)
        if identifier in seen:
            continue
        seen.add(identifier)
        row = rows_by_id.get(identifier)
        if row is None:
            continue
        result.append(row)
        pending.extend(children.get(identifier, []))
    return result


def _ancestors(rows_by_id: dict[int, dict[str, Any]], row: dict[str, Any]) -> Iterable[dict[str, Any]]:
    parent = _get(row, "parent_id", "parentId")
    seen: set[int] = set()
    while isinstance(parent, int) and parent > 0 and parent not in seen:
        seen.add(parent)
        current = rows_by_id.get(parent)
        if current is None:
            break
        yield current
        parent = _get(current, "parent_id", "parentId")


def _is_allowed_parent(name: Any) -> bool:
    return isinstance(name, str) and ALLOWED_PARENT.fullmatch(name) is not None


def _binder_indexes(rows_by_id, flows, releases):
    by_scope, by_release, handlers = {}, {}, []
    for position, flow in enumerate(flows):
        send = rows_by_id.get(flow.get("slice_out"))
        if send is not None and send.get("name") == "binder transaction async":
            for ancestor in _ancestors(rows_by_id, send):
                by_scope.setdefault(ancestor["id"], []).append(position)
    for row in releases:
        key = (row.get("binder_id"), row.get("pid"), row.get("tid"))
        by_release.setdefault(key, []).append(row)
    for row in rows_by_id.values():
        if row.get("name") == "setTransactionState":
            handlers.append(row)
    return by_scope, by_release, handlers


def _binder_paths(
    acquire: dict[str, Any],
    rows_by_id: dict[int, dict[str, Any]],
    flows: list[dict[str, Any]],
    releases: list[dict[str, Any]],
    indexes=None,
) -> list[tuple[dict[str, Any], dict[str, Any], dict[str, Any], dict[str, Any]]]:
    scopes = [acquire] + [row for row in _ancestors(rows_by_id, acquire)
                          if _is_allowed_parent(row.get("name")) and _same_owner_call(row, acquire)]
    scope_ids = {row["id"] for row in scopes}
    paths = []
    selected_flows = flows if indexes is None else [flows[position] for position in sorted(
        {position for scope in scope_ids for position in indexes[0].get(scope, [])})]
    for flow in selected_flows:
        send = rows_by_id.get(flow.get("slice_out"))
        receive = rows_by_id.get(flow.get("slice_in"))
        if send is None or receive is None or send.get("name") != "binder transaction async":
            continue
        if not any(row["id"] in scope_ids and _same_owner_call(row, send)
                   for row in _ancestors(rows_by_id, send)):
            continue
        if (receive.get("name") != "binder async rcv" or
                receive.get("process_name") != "/system/bin/surfaceflinger" or
                receive.get("uid") != 1000):
            continue
        binder_id = send.get("binder_id")
        if not isinstance(binder_id, int) or binder_id <= 0 or receive.get("binder_id") != binder_id:
            continue
        release_rows = releases if indexes is None else indexes[1].get(
            (binder_id, receive.get("pid"), receive.get("tid")), [])
        matching = [row for row in release_rows if row.get("binder_id") == binder_id
                    and row.get("pid") == receive.get("pid") and row.get("tid") == receive.get("tid")]
        _require(len(matching) == 1, "Binder transaction has no unique receiver buffer release")
        release = matching[0]
        _require(send["ts"] <= receive["ts"] < release["ts"], "Binder message timestamps are not ordered")
        # The kernel message identity bounds the server dispatch. Require one
        # setTransactionState in it, rather than matching nearby client times.
        handler_rows = rows_by_id.values() if indexes is None else indexes[2]
        handlers = [row for row in handler_rows if row.get("name") == "setTransactionState"
                    and row.get("pid") == receive.get("pid") and row.get("tid") == receive.get("tid")
                    and receive["ts"] <= row["ts"] and _slice_end(row) <= release["ts"]]
        _require(len(handlers) == 1, "Binder dispatch has no unique setTransactionState handler")
        paths.append((send, receive, release, handlers[0]))
    return paths


def _normalize_transaction(row: dict[str, Any]) -> dict[str, Any]:
    result = {
        "snapshotId": _get(row, "snapshotId", "snapshot_id"),
        "transactionId": _get(row, "transactionId", "transaction_id"),
        "postTime": _get(row, "postTime", "post_time"),
        "uid": _get(row, "uid"),
        "layerId": _get(row, "layerId", "layer_id"),
        "layerName": _get(row, "layerName", "layer_name"),
        "bufferId": _get(row, "bufferId", "buffer_id"),
        "frameNumber": _get(row, "frameNumber", "frame_number"),
        "width": _get(row, "width"),
        "height": _get(row, "height"),
    }
    for field in ("transactionId", "postTime", "uid", "layerId", "bufferId", "frameNumber", "width", "height"):
        _require(isinstance(result[field], int) and not isinstance(result[field], bool),
                 f"transaction {field} is missing or not an integer")
    return result


def _swap_path(
    swap: dict[str, Any],
    rows_by_id: dict[int, dict[str, Any]],
    children: dict[int, list[int]],
    consumer_base: str = CONSUMER_BASE,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], str]:
    descendants = _descendants(rows_by_id, children, _int(_get(swap, "id"), "swap id"))
    egl = [row for row in descendants if _get(row, "name") == "eglSwapBuffers"]
    paths: list[tuple[dict[str, Any], dict[str, Any], dict[str, Any], str]] = []
    for egl_row in egl:
        _require(_same_owner_call(swap, egl_row), "eglSwapBuffers is not an owned nested descendant")
        egl_descendants = _descendants(rows_by_id, children, _int(_get(egl_row, "id"), "eglSwapBuffers id"))
        for on_frame in egl_descendants:
            pattern = r"^onFrameAvailable - (" + re.escape(consumer_base) + r"#[0-9]+)\(f:[0-9]+,a:[0-9]+\)$"
            match = re.fullmatch(pattern, str(_get(on_frame, "name")))
            if match is None:
                continue
            nearest_queue = next(
                (ancestor for ancestor in _ancestors(rows_by_id, on_frame)
                 if _get(ancestor, "name") == "queueBuffer"),
                None,
            )
            if nearest_queue is not None:
                _require(_same_owner_call(egl_row, nearest_queue), "queueBuffer is not an owned nested descendant")
                _require(_same_owner_call(nearest_queue, on_frame), "onFrameAvailable is not an owned nested descendant")
                paths.append((egl_row, nearest_queue, on_frame, match.group(1)))
    _require(len(paths) == 1, "swap has no unique eglSwapBuffers->queueBuffer->onFrameAvailable path")
    return paths[0]


def _same_owner_call(parent: dict[str, Any], child: dict[str, Any]) -> bool:
    parent_pid = _get(parent, "pid", "processPid")
    parent_tid = _get(parent, "tid", "threadTid")
    child_pid = _get(child, "pid", "processPid")
    child_tid = _get(child, "tid", "threadTid")
    if parent_pid is None or parent_tid is None or child_pid != parent_pid or child_tid != parent_tid:
        return False
    parent_start = _int(_get(parent, "ts", "start_ts"), "parent timestamp")
    child_start = _int(_get(child, "ts", "start_ts"), "child timestamp")
    return parent_start <= child_start and _slice_end(child) <= _slice_end(parent)


def _frame_event_binding(events: list[dict[str, Any]], layer_id: int, frame_number: int,
                         target_layer: str = TARGET_LAYER) -> tuple[dict[str, Any], dict[str, Any], int | None]:
    layer = f"{target_layer}#{layer_id}"
    matching = [
        event for event in events
        if _get(event, "layerName", "layer_name") == layer and
        _get(event, "frameNumber", "frame_number") == frame_number
    ]
    queues = [event for event in matching if _get(event, "name") == "Queue"]
    _require(len(queues) == 1, f"layer {layer} frame {frame_number} does not have exactly one Queue")
    queue = queues[0]
    queue_ts = _int(_get(queue, "ts", "start_ts"), "Queue timestamp")
    latches = sorted(
        [event for event in matching if _get(event, "name") == "Latch" and
         _int(_get(event, "ts", "start_ts"), "Latch timestamp") >= queue_ts],
        key=lambda event: _int(_get(event, "ts", "start_ts"), "Latch timestamp"),
    )
    _require(latches, f"layer {layer} frame {frame_number} has no Latch after Queue")
    proxies = sorted(
        [event for event in matching if _get(event, "name") == "PresentFenceSignaled" and
         _int(_get(event, "ts", "start_ts"), "composition timestamp") >= queue_ts],
        key=lambda event: _int(_get(event, "ts", "start_ts"), "composition timestamp"),
    )
    proxy_time = _int(_get(proxies[0], "ts", "start_ts"), "composition timestamp") if proxies else None
    return queue, latches[0], proxy_time


def _bind_frames(
    frames: list[dict[str, Any]],
    slices: list[dict[str, Any]],
    frame_events: list[dict[str, Any]],
    transactions: list[dict[str, Any]],
    *,
    owner_uid: int,
    owner_pid: int,
    trace_loss: list[dict[str, Any]] | None = None,
    binder_flows: list[dict[str, Any]] | None = None,
    binder_releases: list[dict[str, Any]] | None = None,
    target_layer: str = TARGET_LAYER,
    fixture_shape: bool = True,
    interval_kind: str = "readback",
) -> dict[str, Any]:
    """Bind normalized trace rows to the eight readback identities.

    The helper deliberately accepts normalized rows so mutation tests can exercise every
    ancestry and identity gate without importing the Perfetto trace processor.
    """
    _require(not trace_loss, "trace stats report nonzero error or data_loss")
    _require(interval_kind in ("readback", "submission") and (not fixture_shape or interval_kind == "readback"),
             "invalid native timing interval evidence")
    _require(bool(frames), "no captured frames")
    if fixture_shape:
        _require(len(frames) == 8, "raw fixture must contain exactly eight frames")
    by_token: dict[int, dict[str, Any]] = {}
    for frame in frames:
        token = _int(frame.get("token"), "raw frame token")
        _require(token > 0 and token not in by_token, "raw frame tokens are invalid or duplicated")
        by_token[token] = frame
    if fixture_shape:
        _require(set(by_token) == set(range(1, 9)), "raw frame tokens are not exactly 1 through 8")
    consumer_base = target_layer.removesuffix("(BLAST)")
    rows_by_id: dict[int, dict[str, Any]] = {}
    children: dict[int, list[int]] = {}
    for row in slices:
        identifier = _int(_get(row, "id"), "slice id")
        _require(identifier not in rows_by_id, f"duplicate slice id {identifier}")
        rows_by_id[identifier] = row
        parent = _get(row, "parent_id", "parentId")
        if isinstance(parent, int) and parent > 0:
            children.setdefault(parent, []).append(identifier)

    swaps: list[dict[str, Any]] = []
    # UID authority is the Binder caller credential captured by SurfaceFlinger
    # for this dispatch, not process_stats' one-time pre-setuid process snapshot.
    for row in slices:
        match = SWAP.fullmatch(str(_get(row, "name")))
        if match is None:
            continue
        token, egl, native = map(int, match.groups())
        if _get(row, "pid", "processPid") == owner_pid:
            swaps.append({**row, "token": token, "eglFrameId": egl, "nativeNanos": native})
    swaps_by_frame, acquires_by_frame, transactions_by_frame, events_by_frame = {}, {}, {}, {}
    for swap in swaps:
        swaps_by_frame.setdefault((swap["token"], swap["eglFrameId"]), []).append(swap)
    for row in slices:
        if _get(row, "pid", "processPid") == owner_pid:
            match = ACQUIRE.fullmatch(str(_get(row, "name")))
            if match is not None:
                acquires_by_frame.setdefault((match[1], int(match[2])), []).append(row)
    for raw in transactions:
        transaction = _normalize_transaction(raw)
        key = (transaction["uid"], transaction["transactionId"] >> 32, transaction["frameNumber"],
               transaction["width"], transaction["height"])
        transactions_by_frame.setdefault(key, []).append(transaction)
    for event in frame_events:
        key = (_get(event, "layerName", "layer_name"), _get(event, "frameNumber", "frame_number"))
        events_by_frame.setdefault(key, []).append(event)
    binder_indexes = _binder_indexes(rows_by_id, binder_flows or [], binder_releases or [])
    bindings: list[dict[str, Any]] = []
    epoch_consumers: dict[int, set[str]] = {}
    epoch_layers: dict[int, set[int]] = {}
    root_threads: set[tuple[int, int]] = set()
    for token in sorted(by_token):
        frame = by_token[token]
        egl = _int(frame.get("eglFrameId"), f"frame {token} EGL frame id")
        if interval_kind == "submission":
            issued = _int(frame.get("submittedAtNanos"), f"frame {token} submission start")
            duration = _int(frame.get("renderSubmissionDurationNanos"), f"frame {token} submission duration")
            _require(issued > 0 and duration >= 0, "invalid sealed submission interval")
            swap_time = issued + duration
        else:
            issued = _int(frame.get("captureIssuedMonotonicNs"), f"frame {token} issue time")
            swap_time = _int(frame.get("swapCompletedMonotonicNs"), f"frame {token} swap time")
        candidates = swaps_by_frame.get((token, egl), [])
        _require(len(candidates) == 1, f"frame {token} has no unique owner swap for token/EGL identity")
        swap = candidates[0]
        root_threads.add((
            _int(_get(swap, "pid", "processPid"), f"frame {token} producer PID"),
            _int(_get(swap, "tid", "threadTid"), f"frame {token} producer TID"),
        ))
        _require(issued <= swap["nativeNanos"] <= swap_time,
                 f"frame {token} native swap timestamp is outside its {interval_kind} interval")
        egl_slice, queue_buffer, on_frame, consumer = _swap_path(swap, rows_by_id, children, consumer_base)
        expected_width = 64 if fixture_shape else _int(frame.get("width"), "captured viewport width")
        expected_height = 96 if fixture_shape else _int(frame.get("viewportHeight"), "captured viewport height")
        _require(expected_width > 0 and expected_height > 0, "captured viewport dimensions are invalid")
        acquire_rows = acquires_by_frame.get((consumer, egl), [])
        _require(len(acquire_rows) == 1, f"frame {token} has no unique consumer acquire for EGL frame {egl}")
        acquire = acquire_rows[0]
        paths = _binder_paths(acquire, rows_by_id, binder_flows or [], binder_releases or [], binder_indexes)
        _require(paths, f"frame {token} has no causal Binder transaction path")
        post_candidates = []
        for transaction in transactions_by_frame.get((owner_uid, owner_pid, egl, expected_width, expected_height), []):
            if transaction["uid"] != owner_uid or transaction["transactionId"] >> 32 != owner_pid:
                continue
            if transaction["frameNumber"] != egl or transaction["width"] != expected_width or transaction["height"] != expected_height:
                continue
            if transaction["bufferId"] <= 0:
                continue
            for path in paths:
                # Bound the transaction by its exact kernel Binder dispatch.
                # The userspace postTime and ftrace ATRACE entry are separate
                # clock observations; the nested handler is still mandatory,
                # but its marker must not substitute for the dispatch boundary.
                receive, release = path[1:3]
                if receive["ts"] <= transaction["postTime"] < release["ts"]:
                    post_candidates.append((transaction, path))
        _require(len(post_candidates) == 1, f"frame {token} has ambiguous or missing transaction candidate")
        transaction, (send, receive, release, scope) = post_candidates[0]
        _require(transaction["layerName"] == target_layer,
                 f"frame {token} transaction layer is not the exact SurfaceView layer")
        consumer_match = re.fullmatch(re.escape(consumer_base) + r"#([0-9]+)", consumer)
        _require(consumer_match is not None, f"frame {token} consumer is not the exact target layer")
        # BLAST's process-local consumer counter and SurfaceFlinger's layer id
        # are separate namespaces. The owned transaction supplies the layer id.
        layer_id = transaction["layerId"]
        _require(layer_id > 0, f"frame {token} transaction layer id is not positive")
        queue_event, latch_event, proxy_time = _frame_event_binding(
            events_by_frame.get((f"{target_layer}#{layer_id}", egl), []), layer_id, egl, target_layer)
        epoch = _int(frame.get("surfaceEpoch"), f"frame {token} surface epoch")
        epoch_consumers.setdefault(epoch, set()).add(consumer)
        epoch_layers.setdefault(epoch, set()).add(layer_id)
        bindings.append({
            "token": token,
            "sessionId": _int(frame.get("sessionId"), f"frame {token} session"),
            "rendererEpoch": _int(frame.get("rendererEpoch"), f"frame {token} renderer epoch"),
            "surfaceEpoch": epoch,
            "eglFrameId": egl,
            "producerPid": owner_pid,
            "producerTid": _int(_get(swap, "tid", "threadTid"), f"frame {token} producer TID"),
            "producerUid": owner_uid,
            "producerUidEvidence": "surfaceflinger_transaction_binder_calling_uid",
            "processMetadataUid": _get(swap, "uid", "processUid"),
            "consumerName": consumer,
            "consumerPid": _int(_get(acquire, "pid", "processPid"), f"frame {token} consumer PID"),
            "consumerTid": _int(_get(acquire, "tid", "threadTid"), f"frame {token} consumer TID"),
            "layerId": layer_id,
            "bufferId": transaction["bufferId"],
            "bufferWidth": transaction["width"],
            "bufferHeight": transaction["height"],
            "transactionId": transaction["transactionId"],
            "transactionPostTimeNanos": transaction["postTime"],
            "transactionScopeSliceId": _int(_get(scope, "id"), f"frame {token} transaction scope"),
            "binderTransactionId": send["binder_id"],
            "binderSendSliceId": send["id"],
            "binderReceiveSliceId": receive["id"],
            "binderReleaseEventId": release["id"],
            "swapSliceId": _int(_get(swap, "id"), f"frame {token} swap slice"),
            "eglSwapSliceId": _int(_get(egl_slice, "id"), f"frame {token} eglSwapBuffers slice"),
            "queueBufferSliceId": _int(_get(queue_buffer, "id"), f"frame {token} queueBuffer slice"),
            "onFrameAvailableSliceId": _int(_get(on_frame, "id"), f"frame {token} onFrameAvailable slice"),
            "acquireSliceId": _int(_get(acquire, "id"), f"frame {token} acquire slice"),
            "queueSliceId": _int(_get(queue_event, "id"), f"frame {token} Queue event"),
            "latchSliceId": _int(_get(latch_event, "id"), f"frame {token} Latch event"),
            "compositionProxyTimeNanos": proxy_time,
        })
    _require(len(root_threads) == 1, "swap roots do not share one producer PID/TID")
    epochs = set(epoch_consumers)
    _require(all(epoch > 0 for epoch in epochs) and epochs == set(epoch_layers), "surface epochs are invalid")
    if fixture_shape:
        _require(epochs == {1, 2}, "surface epochs do not cover exactly 1 and 2")
    _require(all(len(epoch_consumers[epoch]) == 1 for epoch in epochs), "each surface epoch has multiple consumers")
    _require(all(len(epoch_layers[epoch]) == 1 for epoch in epochs), "each surface epoch has multiple layer ids")
    _require(len({next(iter(epoch_consumers[e])) for e in epochs}) == len(epochs), "surface recreation reused a consumer")
    _require(len({next(iter(epoch_layers[e])) for e in epochs}) == len(epochs), "surface recreation reused a layer id")
    return {
        "producerLayerBindingVerified": True,
        "nativeTimingIntervalEvidence": interval_kind,
        "observableLatchVerified": True,
        "physicalPresentationVerified": False,
        "physicalPresentationTimeNanos": None,
        "corpusCredit": 0,
        "frameCount": len(bindings),
        "surfaceEpochs": sorted(epochs),
        "bindings": sorted(bindings, key=lambda item: item["token"]),
    }


def bind_frames(frames, slices, frame_events, transactions, **kwargs):
    """The original eight-frame/two-surface fixture contract remains mandatory."""
    return _bind_frames(frames, slices, frame_events, transactions,
                        target_layer=TARGET_LAYER, fixture_shape=True, **kwargs)


def bind_live_frames(frames, slices, frame_events, transactions, **kwargs):
    """Bind every recorded normal-viewer frame, including its exact engine input identity."""
    target = "SurfaceView[ml.melun.mangaview/ml.melun.mangaview.activity.ViewerActivity](BLAST)"
    report = _bind_frames(frames, slices, frame_events, transactions,
                          target_layer=target, fixture_shape=False, **kwargs)
    by_id = {row["id"]: row for row in slices}
    by_token = {frame["token"]: frame for frame in frames}
    for binding in report["bindings"]:
        frame = by_token[binding["token"]]
        expected = "engine_frame:" + ":".join(str(_int(frame.get(key), key)) for key in
            ("sessionId", "rendererId", "surfaceEpoch", "token", "inputRevision", "geometryRevision"))
        swap = by_id[binding["swapSliceId"]]
        roots = [row for row in _ancestors(by_id, swap) if row.get("name") == expected and _same_owner_call(row, swap)]
        _require(len(roots) == 1, "swap is not nested in the exact captured engine frame")
        binding["engineFrameSliceId"] = roots[0]["id"]
        binding["inputRevision"] = frame["inputRevision"]
        binding["geometryRevision"] = frame["geometryRevision"]
    return report


def _package_uid(collection: dict[str, Any]) -> int:
    device = collection.get("device")
    text = device.get("packageUid") if isinstance(device, dict) else None
    _require(isinstance(text, str), "collection device packageUid text is missing")
    matches = re.findall(r"(?m)^package:" + re.escape(PACKAGE) + r" uid:([0-9]+)\s*$", text)
    _require(len(matches) == 1, "collection packageUid does not contain exactly the target package line")
    return int(matches[0])


def _load_fixture_frames(fixture: Path, manifest_sha256: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    manifest_path = fixture / "manifest.json"
    _require(manifest_path.is_file(), "fixture manifest is missing")
    _require(_sha256(manifest_path) == _hash(manifest_sha256, "detached fixture manifest sha256"),
             "fixture manifest detached sha256 does not match")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        _fail(f"fixture manifest cannot be decoded: {error}")
    frames: list[dict[str, Any]] = []
    for entry in manifest.get("frames", []):
        raw_path = fixture / entry["rawPacket"]
        raw = raw_path.read_bytes()
        _require(len(raw) >= HEADER.size, f"raw packet {entry['token']} is truncated")
        values = HEADER.unpack(raw[:HEADER.size])
        _require(values[0] == MAGIC and values[1] == 1 and values[2] == 1,
                 f"raw packet {entry['token']} has unsupported identity")
        frames.append({
            "token": values[6],
            "sessionId": values[3],
            "rendererEpoch": values[4],
            "surfaceEpoch": values[5],
            "eglFrameId": values[7],
            "captureIssuedMonotonicNs": values[11],
            "swapCompletedMonotonicNs": values[13],
        })
    return manifest, frames


def _paged_query(processor: Any, query: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for row in processor.query(query):
        rows.append(vars(row))
    return rows


def _load_trace(trace: Path, target_layer: str = TARGET_LAYER, *, full_sort: bool = False,
                bin_path: str | None = None) -> tuple[list[dict[str, Any]], ...]:
    try:
        from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig
    except ImportError as error:
        _fail(f"Perfetto trace processor package is unavailable: {error}")
    with TraceProcessor(trace=str(trace), config=TraceProcessorConfig(load_timeout=120, bin_path=bin_path,
            extra_flags=["--full-sort"] if full_sort else [])) as processor:
        stats = _paged_query(processor, "SELECT name,severity,value FROM stats WHERE value != 0 AND severity IN ('error','data_loss')")
        slices = _paged_query(processor, """
            SELECT s.id,s.parent_id,s.ts,s.dur,s.name,s.track_id,
                   t.utid,t.tid,t.name AS thread_name,
                   p.upid,p.pid,p.uid,p.name AS process_name,
                   EXTRACT_ARG(s.arg_set_id, 'transaction id') AS binder_id
            FROM slice s
            JOIN thread_track tt ON tt.id=s.track_id
            JOIN thread t ON t.utid=tt.utid
            JOIN process p ON p.upid=t.upid
            ORDER BY s.id
        """)
        flows = _paged_query(processor, "SELECT id,slice_out,slice_in FROM flow")
        releases = _paged_query(processor, """
            SELECT e.id,e.ts,t.tid,p.pid,
                   EXTRACT_ARG(e.arg_set_id, 'debug_id') AS binder_id
            FROM ftrace_event e
            JOIN thread t ON t.utid=e.utid
            JOIN process p ON p.upid=t.upid
            WHERE e.name='binder_transaction_buffer_release'
              AND p.name='/system/bin/surfaceflinger'
        """)
        layer_prefix = (target_layer + "#").replace("'", "''")
        events = _paged_query(processor, f"""
            SELECT id,ts,dur,name,layer_name,frame_number
            FROM frame_slice
            WHERE instr(layer_name, '{layer_prefix}') > 0
              AND name IN ('Queue','Latch','PresentFenceSignaled')
            ORDER BY id
        """)
        args = _paged_query(processor, """
            SELECT st.id AS snapshot_id, st.ts AS snapshot_ts,
                   st.arg_set_id, a.key, a.int_value, a.string_value, a.real_value
            FROM surfaceflinger_transactions st
            JOIN args a ON a.arg_set_id = st.arg_set_id
            WHERE instr(a.key, 'transactions[') > 0 OR instr(a.key, 'added_layers[') > 0
            ORDER BY st.id, a.key
        """)
    transactions, layer_names = _normalize_transaction_args(args)
    for row in transactions:
        if row.get("layerName") is None:
            row["layerName"] = layer_names.get(row.get("layerId"))
    return slices, events, transactions, stats, flows, releases


def _arg_value(row: dict[str, Any]) -> Any:
    if row.get("int_value") is not None:
        return row["int_value"]
    if row.get("string_value") is not None:
        return row["string_value"]
    return row.get("real_value")


def _normalize_transaction_args(rows: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[int, str]]:
    transactions: dict[tuple[int, int], dict[str, Any]] = {}
    changes: dict[tuple[int, int, int], dict[str, Any]] = {}
    added: dict[tuple[int, int], dict[str, Any]] = {}
    transaction_re = re.compile(r"^transactions\[([0-9]+)\]\.(.+)$")
    added_re = re.compile(r"^added_layers\[([0-9]+)\]\.(.+)$")
    for row in rows:
        key = row.get("key")
        value = _arg_value(row)
        snapshot = _int(row.get("snapshot_id"), "transaction snapshot id")
        match = transaction_re.fullmatch(str(key))
        if match is not None:
            tx_index = int(match.group(1))
            field = match.group(2)
            tx = transactions.setdefault((snapshot, tx_index), {"snapshotId": snapshot})
            if field.startswith("layer_changes["):
                layer_match = re.match(r"^layer_changes\[([0-9]+)\]\.(.+)$", field)
                if layer_match is None:
                    continue
                layer_index = int(layer_match.group(1))
                change_field = layer_match.group(2)
                change = changes.setdefault((snapshot, tx_index, layer_index), {"snapshotId": snapshot})
                if change_field.startswith("buffer_data."):
                    change[change_field[len("buffer_data."):]] = value
                else:
                    change[change_field] = value
            else:
                tx[field] = value
            continue
        match = added_re.fullmatch(str(key))
        if match is not None:
            added[(snapshot, int(match.group(1)))] = {**added.get((snapshot, int(match.group(1))), {}), match.group(2): value}
    layer_names: dict[int, str] = {}
    for record in added.values():
        if isinstance(record.get("layer_id"), int) and isinstance(record.get("name"), str):
            layer_names[record["layer_id"]] = record["name"]
    result: list[dict[str, Any]] = []
    for (snapshot, tx_index, layer_index), change in changes.items():
        tx = transactions.get((snapshot, tx_index), {})
        if not all(field in tx for field in ("transaction_id", "post_time", "uid")):
            continue
        row = {
            "snapshotId": snapshot,
            "transactionId": tx.get("transaction_id"),
            "postTime": tx.get("post_time"),
            "uid": tx.get("uid"),
            "layerId": change.get("layer_id"),
            "layerName": layer_names.get(change.get("layer_id")),
            "bufferId": change.get("buffer_id"),
            "frameNumber": change.get("frame_number"),
            "width": change.get("width"),
            "height": change.get("height"),
            "transactionIndex": tx_index,
            "layerChangeIndex": layer_index,
        }
        if all(row.get(field) is not None for field in ("layerId", "bufferId", "frameNumber", "width", "height")):
            result.append(row)
    return result, layer_names


def verify_collection(directory: Path | str, collection_sha256: str, manifest_sha256: str) -> dict[str, Any]:
    root = Path(directory).resolve()
    _require(root.is_dir(), f"collection directory does not exist: {directory}")
    collection_path = root / "collection.json"
    _require(collection_path.is_file(), "collection.json is missing")
    _require(_sha256(collection_path) == _hash(collection_sha256, "detached collection sha256"),
             "detached collection sha256 does not match collection.json")
    try:
        collection = json.loads(collection_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        _fail(f"collection.json cannot be decoded: {error}")
    _require(collection.get("classification") == "FIXTURE_ONLY_NO_CORPUS_CREDIT",
             "collection is not fixture-only")
    _require(collection.get("success") is True and collection.get("instrumentationSuccess") is True,
             "collection did not report success")
    _require(collection.get("instrumentationTestCount") == 7, "collection did not report exactly seven passing tests")
    _require(collection.get("singleNewFixtureDirectory") is True and
             isinstance(collection.get("createdFixtureDirectories"), list) and
             len(collection["createdFixtureDirectories"]) == 1,
             "collection did not produce exactly one new fixture directory")
    for local, device in (("localApkSha256", "deviceApkSha256"), ("localTestApkSha256", "deviceTestApkSha256")):
        _require(_hash(collection.get(local), local) == _hash(collection.get(device), device),
                 f"{local} and {device} do not match")
    owner_uid = _package_uid(collection)
    fixture = root / collection["createdFixtureDirectories"][0]
    _require(fixture.resolve().parent == root and re.fullmatch(r"run-[0-9]+", fixture.name) is not None,
             "fixture directory escapes its collection")
    _require(verify_fixture(fixture, manifest_sha256).get("fixturePixelIdentityVerified") is True,
             "readback pixel verifier did not pass")
    manifest, frames = _load_fixture_frames(fixture, manifest_sha256)
    trace = root / "trace.pftrace"
    _require(trace.is_file(), "trace.pftrace is missing")
    _require(_sha256(trace) == _hash(collection.get("traceSha256"), "collection trace sha256"),
             "trace sha256 does not match collection.json")
    slices, events, transactions, stats, flows, releases = _load_trace(trace)
    _require(not stats, "trace reports nonzero error or data_loss statistics")
    swap_pids = {
        _get(row, "pid", "processPid") for row in slices
        if SWAP.fullmatch(str(_get(row, "name")))
    }
    _require(len(swap_pids) == 1 and isinstance(next(iter(swap_pids)), int),
             "trace does not identify one owner PID for viewer swaps")
    owner_pid = next(iter(swap_pids))
    report = bind_frames(frames, slices, events, transactions, owner_uid=owner_uid, owner_pid=owner_pid,
                         trace_loss=stats, binder_flows=flows, binder_releases=releases)
    report.update({
        "fixturePixelIdentityVerified": True,
        "traceSha256": _sha256(trace),
        "collectionSha256": _sha256(collection_path),
        "surfaceFlingerOwnershipProof": True,
        "note": "Exact Binder message, producer buffer, SurfaceFlinger layer and latch binding; physical scanout is unmeasured.",
        "ownerUid": owner_uid,
        "ownerPid": owner_pid,
        "corpusCredit": 0,
    })
    return report


def _failure_report(reason: str) -> dict[str, Any]:
    return {
        "fixturePixelIdentityVerified": False,
        "producerLayerBindingVerified": False,
        "observableLatchVerified": False,
        "physicalPresentationVerified": False,
        "physicalPresentationTimeNanos": None,
        "surfaceFlingerOwnershipProof": False,
        "corpusCredit": 0,
        "frameCount": 0,
        "bindings": [],
        "error": reason,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--collection-sha256", required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args(argv)
    try:
        report = verify_collection(arguments.directory, arguments.collection_sha256, arguments.manifest_sha256)
        exit_code = 0
    except (OSError, SurfaceFixtureError, ValueError) as error:
        report = _failure_report(str(error))
        exit_code = 1
    try:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except OSError as error:
        print(f"cannot write verifier output: {error}", file=sys.stderr)
        return 1
    if exit_code:
        print(report["error"], file=sys.stderr)
    else:
        print(json.dumps({"output": str(arguments.output.resolve()), "success": True}, sort_keys=True))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
