"""Bind every natural readback in one normal-viewer trace. Never claims physical scanout."""
import argparse
import json
from pathlib import Path
import re
import subprocess

from verify_engine_surface_fixture import (
    HEADER, MAGIC, PACKAGE, SurfaceFixtureError, _load_trace, _package_uid, _require, _sha256, bind_live_frames,
)

TARGET = 'SurfaceView[ml.melun.mangaview/ml.melun.mangaview.activity.ViewerActivity](BLAST)'


def verify_native_packet(frame, raw, strip):
    _require(len(raw) >= HEADER.size, 'native packet is truncated')
    values = HEADER.unpack_from(raw)
    _require(values[:3] == (MAGIC, 1, 1) and values[15] == 0, 'native header status/version/physical flag is invalid')
    fields = ('sessionId', 'rendererEpoch', 'surfaceEpoch', 'token', 'eglFrameId', 'width', 'top', 'bottom',
              'issuedMonotonicNs', 'readyMonotonicNs', 'swapCompletedMonotonicNs', 'rgbaBytes')
    for index, key in enumerate(fields, 3):
        _require(type(frame.get(key)) is int and frame[key] == values[index], f'native header disagrees with {key}')
    _require(all(value > 0 for value in values[3:9]), 'native identity/dimensions are invalid')
    _require(0 <= values[9] < values[10] and values[14] == values[8] * (values[10] - values[9]) * 4,
             'native geometry/payload size is invalid')
    _require(0 < values[11] <= values[13] <= values[12], 'native timestamps are not ordered')
    _require(len(raw) == HEADER.size + values[14] and raw[HEADER.size:] == strip,
             'native pixel payload disagrees with captured strip')


def verify(directory, trace_processor=None):
    root = Path(directory).resolve()
    collection = json.loads((root / 'collection.json').read_text(encoding='utf-8'))
    _require(collection.get('classification') == 'LIVE_DIAGNOSTIC_NO_CORPUS_CREDIT', 'wrong collection classification')
    _require(collection.get('success') is True and collection.get('traceStopped') is True, 'collection did not complete')
    names = collection.get('captureDirectories')
    _require(isinstance(names, list) and len(names) == 1 and re.fullmatch(r'engine-capture-[0-9]+', names[0]),
             'collection does not contain one exact capture directory')
    capture = (root / names[0]).resolve()
    _require(capture.parent == root, 'capture directory escapes collection')
    summary = json.loads((capture / 'summary.json').read_text(encoding='utf-8'))
    files = list(capture.glob('frame-*.json'))
    _require(summary.get('capturedFrames') == len(files) and bool(files), 'captured frame count mismatch')
    frames = []
    hashes = {}
    indices = set()
    for path in files:
        match = re.fullmatch(r'frame-([0-9]+).json', path.name)
        _require(match is not None and not path.is_symlink(), 'invalid frame file')
        index = int(match.group(1))
        indices.add(index)
        frame = json.loads(path.read_text(encoding='utf-8'))
        _require(frame.get('status') == 'OK' and frame.get('forcedScene') is False, 'capture is not a successful natural frame')
        strip = capture / f'strip-{index}.rgba'
        _require(strip.is_file() and not strip.is_symlink(), 'captured strip is missing')
        _require(strip.stat().st_size == frame['rgbaBytes'] == frame['width'] * (frame['bottom'] - frame['top']) * 4,
                 'strip size does not match its frame geometry')
        _require(0 <= frame['top'] < frame['bottom'] <= frame['viewportHeight'], 'invalid captured viewport strip')
        native = capture / f'native-{index}.packet'
        _require(native.is_file() and not native.is_symlink(), 'original native packet is missing')
        verify_native_packet(frame, native.read_bytes(), strip.read_bytes())
        frame['captureIssuedMonotonicNs'] = frame['issuedMonotonicNs']
        hashes[str(path.relative_to(root))] = _sha256(path)
        hashes[str(strip.relative_to(root))] = _sha256(strip)
        hashes[str(native.relative_to(root))] = _sha256(native)
        frames.append(frame)
    _require(indices == set(range(len(files))), 'captured frames have missing indices')
    trace = root / 'display.perfetto-trace'
    _require(_sha256(trace) == collection.get('traceSha256'), 'trace digest mismatch')
    from perfetto.trace_processor.platform import PlatformDelegate
    binary = Path(PlatformDelegate().get_shell_path(str(trace_processor) if trace_processor else None)).resolve()
    processor_identity = {'sha256': _sha256(binary), 'version': subprocess.check_output(
        [str(binary), '--version'], text=True).strip()}
    slices, events, transactions, stats, flows, releases = _load_trace(
        trace, TARGET, full_sort=True, bin_path=str(binary))
    first = min(frames, key=lambda frame: frame['token'])
    owner_uid = _package_uid(collection)
    owner_pid = first['processId']
    _require(isinstance(owner_pid, int) and owner_pid > 0, 'invalid captured process identity')
    _require(all(frame['processId'] == owner_pid and frame['processUid'] == owner_uid for frame in frames),
             'captured process identities do not match the installed package')
    first_name = 'engine_frame:' + ':'.join(str(first[key]) for key in
        ('sessionId', 'rendererId', 'surfaceEpoch', 'token', 'inputRevision', 'geometryRevision'))
    pids = {row['pid'] for row in slices if row['name'] == first_name}
    _require(pids == {owner_pid}, 'capture has no unique matching viewer process')
    foreign_audit = None
    if stats:
        from audit_engine_foreign_timeline import audit
        tap = None
        launch_path = capture / 'ui-launch.json'
        if collection.get('catalogUi') is True:
            launch = json.loads(launch_path.read_bytes())
            _require(launch['entry'] == 'CATALOG_EPISODE_ROW_TAP', 'missing real catalog tap evidence')
            tap = launch['tapStartedMonotonicNs']
            _require(type(tap) is int and 0 < tap <= min(f['issuedMonotonicNs'] for f in frames),
                     'episode tap is not before captured frames')
            hashes[str(launch_path.relative_to(root))] = _sha256(launch_path)
        foreign_audit = audit(trace, stats, owner_pid, owner_uid, str(binary), tap_monotonic_ns=tap)
        _require(foreign_audit['originalTraceSha256'] == collection['traceSha256'], 'audited original trace changed')
    raw_clock = None
    if collection.get('rawMonotonicFtrace') is True:
        from engine_raw_trace import load_raw_rows
        slices, events, flows, releases, raw_clock = load_raw_rows(
            root, collection, slices, stats, foreign_audit, owner_pid)
        for name in ('frames.jsonl', 'renderer-close.json'):
            hashes[str((capture / name).relative_to(root))] = _sha256(capture / name)
        hashes['raw-clock-verification.json'] = _sha256(root / 'raw-clock-verification.json')
    result = bind_live_frames(frames, slices, events, transactions, owner_uid=owner_uid,
        owner_pid=owner_pid, trace_loss=[] if foreign_audit else stats, binder_flows=flows, binder_releases=releases)
    result['originalTraceStats'] = stats
    result['outOfEpisodeFrameEndAudit'] = foreign_audit
    result['kernelTimingSource'] = 'ORIGINAL_RAW_MONOTONIC' if raw_clock else 'TRACE_PROCESSOR_CONVERTED'
    result.update(classification='LIVE_DIAGNOSTIC_NO_CORPUS_CREDIT', traceSha256=collection['traceSha256'],
        collectionSha256=_sha256(root / 'collection.json'), capturedFilesSha256=hashes,
        traceProcessorFullSort=True, traceProcessor=processor_identity,
        nativePacketVerified=True, independentSourcePixelsVerified=False, wholeEpisodeVerified=False,
        note='Exact engine input frame, Binder message, producer buffer and SF latch; physical display time remains unknown.')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--trace-processor', type=Path, help='Explicit official trace processor binary; version and SHA256 are recorded')
    args = parser.parse_args()
    try:
        result = verify(args.directory, args.trace_processor)
        success = True
    except (OSError, ValueError, KeyError, TypeError) as failure:
        result = {'error': str(failure), 'producerLayerBindingVerified': False,
                  'physicalPresentationVerified': False, 'corpusCredit': 0}
        success = False
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({'success': success, 'frameCount': result.get('frameCount'), 'error': result.get('error')}))
    return 0 if success else 1


if __name__ == '__main__':
    raise SystemExit(main())
