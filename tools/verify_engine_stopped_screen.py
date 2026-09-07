"""Bind a stable stopped viewer buffer to real compositor screenshots and the original SF trace."""
import hashlib
import json
from pathlib import Path
import re

from compare_engine_stopped_screen import verify as verify_pixels
from engine_source_row_coverage import require
from verify_engine_frame_observations import verify_rows as verify_frames
from verify_engine_input_observations import verify_rows as verify_inputs
from verify_engine_live_surface import TARGET
from verify_engine_surface_fixture import _load_trace, _package_uid, bind_live_frames


def stable_interval(records, frames, inputs, binding, events, transactions, owner_pid, owner_uid):
    require(len(records) == 2, 'stopped interval requires two screenshots')
    stable = lambda value: {k: v for k, v in value.items() if k != 'observedMonotonicNs'}
    snapshot = records[0]['before']
    require(all(stable(record[side]) == stable(snapshot) for record in records for side in ('before', 'after')),
            'viewer scene changed across the stopped interval')
    start = records[0]['captureStartedMonotonicNs']
    end = records[1]['captureCompletedMonotonicNs']
    require(records[1]['captureStartedMonotonicNs'] - records[0]['captureCompletedMonotonicNs'] >= 1_000_000_000,
            'stopped screenshot interval is shorter than one second')
    keys = ('rendererId', 'sessionId', 'rendererEpoch', 'surfaceEpoch', 'token', 'inputRevision', 'geometryRevision', 'eglFrameId')
    selected = [frame for frame in frames if all(frame[k] == snapshot[k] for k in keys)]
    require(len(selected) == 1, 'stopped scene lacks one exact sealed renderer frame')
    submitted = [frame for frame in frames if frame['rendererId'] == snapshot['rendererId'] and frame['submittedAtNanos'] <= end]
    require(submitted and max(submitted, key=lambda f: f['token'])['token'] == snapshot['token'],
            'a newer native viewer frame was submitted during the stopped interval')
    require(selected[0]['submittedAtNanos'] + selected[0]['renderSubmissionDurationNanos'] <= start,
            'stopped frame submission overlaps screenshot acquisition')
    require(not any(start <= row['acceptedAtNanos'] <= end or
                    row.get('resolvedAtNanos') is not None and start <= row['resolvedAtNanos'] <= end for row in inputs),
            'viewer input was accepted or resolved during the stopped interval')
    require(binding['producerPid'] == owner_pid and binding['producerUid'] == owner_uid and
            binding['token'] == snapshot['token'] and binding['eglFrameId'] == snapshot['eglFrameId'],
            'stopped SF binding belongs to another owner/frame')
    layer = TARGET + '#' + str(binding['layerId'])
    layer_events = [event for event in events if event['layer_name'] == layer]
    latches = [event for event in layer_events if event['name'] == 'Latch' and event['ts'] <= start]
    require(latches and max(latches, key=lambda event: event['ts'])['frame_number'] == snapshot['eglFrameId'],
            'stopped screenshot does not follow the exact last viewer latch')
    require(not any(event['name'] in ('Queue', 'Latch') and start <= event['ts'] <= end and
                    event['frame_number'] != snapshot['eglFrameId'] for event in layer_events),
            'a different viewer buffer was queued or latched during the stopped interval')
    changes = [tx for tx in transactions if tx['uid'] == owner_uid and tx['transactionId'] >> 32 == owner_pid and
               start <= tx['postTime'] <= end]
    require(not changes, 'an owned buffer transaction changed during the stopped interval')
    return {'stoppedStartMonotonicNs': start, 'stoppedEndMonotonicNs': end, 'token': snapshot['token'],
            'eglFrameId': snapshot['eglFrameId'], 'layerId': binding['layerId'], 'bufferId': binding['bufferId']}


def verify(root):
    root = Path(root).resolve()
    collection = json.loads((root / 'collection.json').read_bytes())
    require(collection.get('success') is True and collection.get('traceStopped') is True, 'collection did not finish')
    names = collection['captureDirectories']
    require(len(names) == 1 and re.fullmatch(r'engine-capture-[0-9]+', names[0]), 'invalid capture directory')
    capture = root / names[0]
    surface_path = root / 'surface.json'
    surface = json.loads(surface_path.read_bytes())
    require(surface.get('producerLayerBindingVerified') is True and surface.get('nativePacketVerified') is True and
            surface['collectionSha256'] == hashlib.sha256((root / 'collection.json').read_bytes()).hexdigest(),
            'complete native/SF collection verification is required')
    trace = root / 'display.perfetto-trace'
    require(hashlib.sha256(trace.read_bytes()).hexdigest() == surface['traceSha256'] == collection['traceSha256'], 'trace changed')
    frames = [json.loads(line) for line in (capture / 'frames.jsonl').read_text().splitlines()]
    inputs = [json.loads(line) for line in (capture / 'inputs.jsonl').read_text().splitlines()]
    verify_frames(frames, json.loads((capture / 'renderer-close.json').read_bytes()))
    input_result = verify_inputs(inputs, json.loads((capture / 'input-close.json').read_bytes()))
    require(input_result.get('completeSessionInputHistoryVerified') is True, 'input history is incomplete')
    pixels = verify_pixels(capture, root / 'original-sources')
    require(pixels.get('compositedScreenshotPixelsVerified') is True, 'stopped screenshot pixels do not match originals')
    records = [json.loads(path.read_bytes()) for path in sorted(capture.glob('stopped-screen-*.json'))]
    before = records[0]['before']
    pid, uid = records[0]['processId'], _package_uid(collection)
    require(all(record['processId'] == pid and record['processUid'] == uid for record in records), 'screenshot owner changed')
    matched = [frame for frame in frames if frame['token'] == before['token'] and frame['rendererId'] == before['rendererId']]
    require(len(matched) == 1, 'stopped screenshot has no exact sealed frame')
    final = matched[0]
    evidence = {**before, 'renderSubmissionDurationNanos': final['renderSubmissionDurationNanos']}
    slices, events, transactions, stats, flows, releases = _load_trace(trace, TARGET, full_sort=True)
    foreign_audit = None
    if stats:
        from audit_engine_foreign_timeline import audit
        tap = None
        if collection.get('catalogUi') is True:
            launch_path = capture / 'ui-launch.json'
            launch_raw = launch_path.read_bytes()
            launch = json.loads(launch_raw)
            require(surface['capturedFilesSha256'].get(str(launch_path.relative_to(root))) ==
                    hashlib.sha256(launch_raw).hexdigest(), 'catalog tap changed since surface verification')
            require(launch['entry'] == 'CATALOG_EPISODE_ROW_TAP', 'missing catalog tap evidence')
            tap = launch['tapStartedMonotonicNs']
            require(type(tap) is int and 0 < tap <= min(f['submittedAtNanos'] for f in frames),
                    'catalog tap is after the viewer started')
        foreign_audit = audit(trace, stats, pid, uid, tap_monotonic_ns=tap)
    if collection.get('rawMonotonicFtrace') is True:
        from engine_raw_trace import load_raw_rows
        for name in ('frames.jsonl', 'renderer-close.json'):
            require(surface['capturedFilesSha256'].get(str((capture / name).relative_to(root))) ==
                    hashlib.sha256((capture / name).read_bytes()).hexdigest(), 'sealed raw journal changed')
        slices, events, flows, releases, _ = load_raw_rows(root, collection, slices, stats, foreign_audit, pid)
        require(surface['capturedFilesSha256'].get('raw-clock-verification.json') ==
                hashlib.sha256((root / 'raw-clock-verification.json').read_bytes()).hexdigest(), 'raw clock proof changed')
    bound = bind_live_frames([evidence], slices, events, transactions, owner_pid=pid, owner_uid=uid,
                            trace_loss=[], binder_flows=flows, binder_releases=releases, interval_kind='submission')
    interval = stable_interval(records, frames, inputs, bound['bindings'][0], events, transactions, pid, uid)
    files = ['frames.jsonl', 'inputs.jsonl', 'renderer-close.json', 'input-close.json',
             'stopped-screen-0.json', 'stopped-screen-0.png', 'stopped-screen-1.json', 'stopped-screen-1.png']
    return {**interval, 'finalStopVerified': True, 'compositedScreenshotPixelsVerified': True,
            'producerLayerBindingVerified': True, 'nativeTimingIntervalEvidence': 'sealed_submission_journal',
            'nativeReadbackOfFinalFrame': False, 'physicalPresentationVerified': False,
            'wholeEpisodeVerified': False, 'corpusCredit': 0, 'bindings': bound['bindings'], 'screens': pixels['screens'],
            'surfaceReportSha256': hashlib.sha256(surface_path.read_bytes()).hexdigest(),
            'capturedFilesSha256': {name: hashlib.sha256((capture / name).read_bytes()).hexdigest() for name in files},
            'scope': 'Original SF trace identifies the unchanged viewer buffer across a >=1s stopped interval. Two actual compositor screenshots match source pixels; no owned input, submission or buffer transaction changed. Physical scanout time is unmeasured.'}
