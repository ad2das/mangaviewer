"""Collect the normal viewer's natural readbacks and concurrent SF trace; no corpus credit."""
import argparse
import hashlib
import gzip
import json
from pathlib import Path
import re
import subprocess
import time

from collect_engine_readback_fixture import (
    PACKAGE, TEST_PACKAGE, _adb_checked, _adb_command, _device_snapshot,
    _installed_apk, _pull, _start_trace, _stop_trace, _validate_device,
)
from verify_engine_live_surface import verify_native_packet
from verify_engine_surface_fixture import HEADER
from engine_capture_archive import archive_device_capture, archive_device_trace
from engine_trace_clock import RawMonotonicTrace, raw_monotonic_config


def _sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    collection_started_ns = time.perf_counter_ns()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--app-apk', type=Path, required=True)
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--traverse-episode', action='store_true', help='Traverse the full original episode in both directions')
    parser.add_argument('--traversal-seconds', type=int, default=90, help='Fixed diagnostic collection bound, not a performance acceptance limit (1..300)')
    parser.add_argument('--maximum-captures', type=int, default=512, help='Bounded diagnostic capture storage (1..1024)')
    parser.add_argument('--catalog-ui', action='store_true', help='Enter through live catalog/search and the real episode row')
    parser.add_argument('--source', choices=('wfwf', 'ntk'), default='wfwf')
    parser.add_argument('--kind', choices=('COMIC', 'WEBTOON'), default='COMIC')
    parser.add_argument('--series-key', default='comic:10001')
    parser.add_argument('--episode-key', default='1')
    parser.add_argument('--catalog-entry', type=Path, help='Previously discovered fixed sample identity/titles; actual UI still verifies identity')
    parser.add_argument('--no-readback', action='store_true', help='Timing control only; no pixel/row qualification')
    parser.add_argument('--gesture-plan', type=Path, help='Fixed boolean direction list for matched timing controls')
    parser.add_argument('--no-trace', action='store_true', help='Fixed no-readback timing control only; cannot qualify display evidence')
    parser.add_argument('--raw-monotonic-ftrace', action='store_true', help='Temporarily own/restore raw MONOTONIC tracefs clock and buffers')
    parser.add_argument('--memory-sampling', action='store_true', help='Collect owned-process PSS boundaries and asynchronous active samples')
    parser.add_argument('--navigation-idle-ms', type=int, help='Experimental UI navigation idle timeout only; restored before viewer input')
    parser.add_argument('--navigation-async-moves', action='store_true', help='Real navigation swipes with asynchronous MOVE injection and synchronous UP')
    args = parser.parse_args()
    if args.navigation_idle_ms is not None and (not args.catalog_ui or not 0 <= args.navigation_idle_ms <= 10_000):
        parser.error('navigation idle override requires catalog UI and 0..10000 milliseconds')
    if args.navigation_async_moves and not args.catalog_ui:
        parser.error('asynchronous navigation moves require catalog UI')
    if args.raw_monotonic_ftrace and args.no_trace:
        parser.error('raw MONOTONIC requires trace collection')
    if not 1 <= args.traversal_seconds <= 300 or not 1 <= args.maximum_captures <= 1024:
        parser.error('diagnostic traversal bounds are outside the supported range')
    if args.no_trace and not (args.no_readback and args.gesture_plan and args.traverse_episode):
        parser.error('--no-trace is restricted to fixed-gesture no-readback controls')
    metadata_args = []
    measurement_args = ['-e', 'captureReadback', str(not args.no_readback).lower(),
                        '-e', 'captureMemory', str(args.memory_sampling).lower(),
                        '-e', 'captureTraversalSeconds', str(args.traversal_seconds),
                        '-e', 'captureMaximumFrames', str(args.maximum_captures)]
    if args.navigation_idle_ms is not None:
        measurement_args += ['-e', 'captureNavigationIdleMillis', str(args.navigation_idle_ms)]
    if args.navigation_async_moves:
        measurement_args += ['-e', 'captureNavigationAsyncMoves', 'true']
    if args.no_readback and not args.traverse_episode:
        parser.error('--no-readback requires --traverse-episode')
    if args.gesture_plan:
        import base64
        if not args.traverse_episode:
            parser.error('--gesture-plan requires --traverse-episode')
        gesture_raw = args.gesture_plan.read_bytes()
        directions = json.loads(gesture_raw)
        if not isinstance(directions, list) or not 1 <= len(directions) <= 512 or not all(type(v) is bool for v in directions):
            parser.error('gesture plan must contain 1..512 boolean directions')
        measurement_args += ['-e', 'captureGesturePlanBase64', base64.b64encode(gesture_raw).decode('ascii')]
    if args.catalog_entry:
        import base64
        if not args.catalog_ui:
            parser.error('--catalog-entry requires --catalog-ui')
        metadata = args.catalog_entry.read_bytes()
        entry = json.loads(metadata)
        if any(entry[k] != v for k, v in {'sourceId': args.source, 'seriesKey': args.series_key,
               'episodeKey': args.episode_key, 'kind': args.kind}.items()):
            parser.error('catalog entry identity differs from requested episode')
        if not all(isinstance(entry.get(k), str) and entry[k].strip() for k in ('seriesTitle', 'episodeTitle')):
            parser.error('catalog entry titles are missing')
        metadata_args = ['-e', 'captureCatalogMetadataBase64', base64.b64encode(metadata).decode('ascii')]
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    if args.catalog_entry:
        (root / 'supplied-catalog-entry.json').write_bytes(metadata)
    if args.gesture_plan:
        (root / 'fixed-gesture-plan.json').write_bytes(gesture_raw)
    remote_root = f'/sdcard/Android/data/{PACKAGE}/files'
    def captures():
        return {line.strip() for line in _adb_checked(args.adb, 'shell', 'ls', '-1', remote_root).splitlines()
                if re.fullmatch(r'engine-capture-[0-9]+', line.strip())}
    report = {'classification': 'LIVE_DIAGNOSTIC_NO_CORPUS_CREDIT', 'corpusCredit': 0,
              'physicalPresentationVerified': False, 'performanceQualified': False}
    phase_started_ns = collection_started_ns
    report['hostPhaseTimings'] = []

    def finish_phase(name):
        nonlocal phase_started_ns
        finished = time.perf_counter_ns()
        report['hostPhaseTimings'].append({'stage': name, 'startedMonotonicNs': phase_started_ns,
            'finishedMonotonicNs': finished, 'durationMillis': (finished - phase_started_ns) / 1_000_000})
        phase_started_ns = finished
    report['traceEnabled'] = not args.no_trace
    report['navigationIdleOverrideMillis'] = args.navigation_idle_ms
    report['navigationAsynchronousMoves'] = args.navigation_async_moves
    if args.no_trace:
        report['classification'] = 'TIMING_CONTROL_WITHOUT_DISPLAY_TRACE'
    trace_pid = None
    raw_clock = None
    report['rawMonotonicFtrace'] = args.raw_monotonic_ftrace
    try:
        report['avd'] = _validate_device(args.adb)
        report['app'] = _installed_apk(args.adb, PACKAGE, _sha256(args.app_apk))
        report['test'] = _installed_apk(args.adb, TEST_PACKAGE, _sha256(args.test_apk))
        report['device'] = _device_snapshot(args.adb)
        before = captures()
        report['capturesBefore'] = sorted(before)
        if not args.no_trace:
            config = Path(__file__).with_name('engine_live_frames.cfg').read_bytes()
            if args.raw_monotonic_ftrace:
                raw_clock = RawMonotonicTrace(args.adb)
                report['rawMonotonicTrace'] = raw_clock.report
                config = raw_monotonic_config(config)
                raw_clock.prepare()
            (root / 'trace.cfg').write_bytes(config)
            remote_trace = f'/data/misc/perfetto-traces/engine-live-{time.time_ns()}.perfetto-trace'
            trace_pid = _start_trace(args.adb, remote_trace, config, root)
            if raw_clock:
                raw_clock.observe('During')
            report['tracePid'] = trace_pid
        report['traverseEpisode'] = args.traverse_episode
        report['traversalSeconds'] = args.traversal_seconds
        report['maximumCaptures'] = args.maximum_captures
        report['readbackEnabled'] = not args.no_readback
        report['memorySamplingEnabled'] = args.memory_sampling
        report['fixedGesturePlanSha256'] = hashlib.sha256(gesture_raw).hexdigest() if args.gesture_plan else None
        report['catalogUi'] = args.catalog_ui
        report['requestedEpisode'] = {'sourceId': args.source, 'seriesKey': args.series_key,
                                      'episodeKey': args.episode_key, 'kind': args.kind}
        finish_phase('environment-and-trace-setup')
        result = subprocess.run(_adb_command(args.adb, 'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'traverseEpisode', str(args.traverse_episode).lower(),
            '-e', 'catalogUi', str(args.catalog_ui).lower(), '-e', 'captureSource', args.source,
            '-e', 'captureKind', args.kind, '-e', 'captureSeries', args.series_key, '-e', 'captureEpisode', args.episode_key,
            *metadata_args,
            *measurement_args,
            '-e', 'class', 'ml.melun.mangaview.activity.EngineViewerCaptureTest',
            f'{TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner'), capture_output=True, check=False)
        text = result.stdout + result.stderr
        (root / 'instrumentation.txt').write_bytes(text)
        report['instrumentationSuccess'] = result.returncode == 0 and b'OK (1 test)' in text and b'FAILURES!!!' not in text
        finish_phase('android-instrumentation')
        if args.no_trace:
            report['traceStopped'] = None
            report['traceSha256'] = None
        else:
            if raw_clock:
                raw_clock.observe('BeforeStop')
            report['traceStopped'] = _stop_trace(args.adb, trace_pid)
            if report['traceStopped']:
                trace_pid = None
            else:
                raise RuntimeError('Trace process has not terminated; preserve its handle for follow-up')
            if raw_clock:
                raw_clock.restore(stopped=True)
            report['tracePull'] = _pull(args.adb, remote_trace, root / 'display.perfetto-trace')
            if report['tracePull']['exit'] != 0:
                raise RuntimeError('Trace pull failed')
            report['traceSha256'] = _sha256(root / 'display.perfetto-trace')
            report['deviceTraceArchive'] = archive_device_trace(
                args.adb, root, remote_trace, stopped=report['traceStopped'])
        finish_phase('trace-stop-transfer-and-archive')
        after = captures()
        report['capturesAfter'] = sorted(after)
        created = after - before
        report['captureDirectories'] = sorted(created)
        report['capturePulls'] = [_pull(args.adb, f'{remote_root}/{name}', root / name) for name in sorted(created)]
        if len(created) == 1 and all(p['exit'] == 0 for p in report['capturePulls']):
            report['deviceCaptureArchive'] = archive_device_capture(args.adb, root, next(iter(created)))
        finish_phase('capture-transfer-and-archive')
        report['derivedStrips'] = []
        for name in sorted(created):
            for frame_path in (root / name).glob('frame-*.json'):
                index = int(frame_path.stem.split('-')[1])
                native_path = frame_path.with_name(f'native-{index}.packet')
                compressed_path = frame_path.with_name(f'native-{index}.packet.gz')
                frame = json.loads(frame_path.read_bytes())
                if compressed_path.exists():
                    packet = gzip.decompress(compressed_path.read_bytes())
                    if hashlib.sha256(packet).hexdigest() != frame.get('nativePacketSha256'):
                        raise ValueError('decompressed original native packet digest mismatch')
                    with native_path.open('xb') as stream:
                        stream.write(packet)
                packet = native_path.read_bytes()
                payload = packet[HEADER.size:]
                verify_native_packet(frame, packet, payload)
                strip_path = frame_path.with_name(f'strip-{index}.rgba')
                if not strip_path.exists():
                    with strip_path.open('xb') as stream:
                        stream.write(payload)
                    report['derivedStrips'].append({'file': str(strip_path.relative_to(root)),
                        'nativeFile': str(native_path.relative_to(root)), 'nativeSha256': _sha256(native_path),
                        'payloadOffset': HEADER.size, 'sha256': _sha256(strip_path)})
        report['success'] = report['instrumentationSuccess'] and len(created) == 1 and all(p['exit'] == 0 for p in report['capturePulls'])
        finish_phase('native-packet-verification-and-derived-strips')
    except Exception as failure:
        report['success'] = False
        report['error'] = str(failure)
    finally:
        if trace_pid is not None:
            report['finalStopObserved'] = _stop_trace(args.adb, trace_pid)
            if report['finalStopObserved']:
                trace_pid = None
        if raw_clock:
            try:
                raw_clock.restore(stopped=trace_pid is None)
            except Exception as failure:
                report['success'] = False
                report['clockRestorationError'] = str(failure)
        finish_phase('final-cleanup')
        report['hostCollectionDurationMillis'] = (time.perf_counter_ns() - collection_started_ns) / 1_000_000
        report['hostTimingClock'] = 'time.perf_counter_ns; independent of device clock'
        (root / 'collection.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({'output': str(root), 'success': report.get('success'), 'error': report.get('error')}))
    return 0 if report.get('success') else 1


if __name__ == '__main__':
    raise SystemExit(main())
