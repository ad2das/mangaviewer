"""Collect alternating paired readback controls with a frozen real-gesture plan; no corpus credit."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

from verify_engine_frame_observations import verify_rows as verify_frames
from verify_engine_input_observations import verify_rows as verify_inputs


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def measurements(root, expected_gestures, enabled):
    collection = json.loads((root / 'collection.json').read_bytes())
    require(collection['success'] is True, 'control collection failed')
    trace_enabled = collection.get('traceEnabled', True)
    require(collection['traceStopped'] is True if trace_enabled else
            collection['classification'] == 'TIMING_CONTROL_WITHOUT_DISPLAY_TRACE' and not enabled,
            'control trace state is invalid')
    require(collection['fixedGesturePlanSha256'] == expected_gestures and collection['readbackEnabled'] is enabled,
            'measurement mode/gesture plan changed')
    capture = root / collection['captureDirectories'][0]
    summary = json.loads((capture / 'summary.json').read_bytes())
    require(summary['fixedGestureMeasurement'] is True and summary['readbackEnabled'] is enabled,
            'capture did not run the fixed measurement plan')
    frames = [json.loads(line) for line in (capture / 'frames.jsonl').read_text().splitlines()]
    result = verify_frames(frames, json.loads((capture / 'renderer-close.json').read_bytes()))
    inputs = verify_inputs([json.loads(line) for line in (capture / 'inputs.jsonl').read_text().splitlines()],
                           json.loads((capture / 'input-close.json').read_bytes()))
    require(inputs['completeSessionInputHistoryVerified'] is True, 'input history is incomplete')
    ownership = json.loads((capture / 'ownership.json').read_bytes())
    for key in ('queued', 'active', 'retiring', 'subscribers', 'retainedResults', 'fileLeases', 'preparedPages', 'pendingPublications'):
        require(ownership[key] == 0, 'measurement left owned resources: ' + key)
    motion = [json.loads(line) for line in (capture / 'injected-motion.jsonl').read_text().splitlines()]
    require(motion and all(row['dispatchAccepted'] is True for row in motion), 'gesture dispatch failed')
    geometry = [{k: row[k] for k in ('gestureOrdinal', 'action', 'xBits', 'yBits', 'source')} for row in motion]
    result.update(readbackEnabled=enabled, traceEnabled=trace_enabled, capturedFrames=summary['capturedFrames'],
                  appSha256=collection['app']['sha256'], testSha256=collection['test']['sha256'],
                  gestureGeometrySha256=hashlib.sha256(json.dumps(geometry, sort_keys=True).encode()).hexdigest(),
                  inputDispatchDurationMillis=motion[-1]['eventTimeMillis'] - motion[0]['eventTimeMillis'],
                  collectionSha256=digest(root / 'collection.json'))
    require(bool(summary['capturedFrames']) is enabled, 'unexpected native readback count')
    (root / 'measurement.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--app-apk', type=Path, required=True)
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--catalog-entry', type=Path, required=True)
    parser.add_argument('--gesture-plan', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--pairs', type=int, choices=(5, 10), default=5)
    parser.add_argument('--raw-monotonic-ftrace', action='store_true')
    parser.add_argument('--start-with', choices=('on', 'off'), default='on',
                        help='First mode, allowing a second five-pair block to continue alternating order')
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    for path, name in ((args.app_apk, 'app.apk'), (args.test_apk, 'test.apk'),
                       (args.catalog_entry, 'catalog-entry.json'), (args.gesture_plan, 'gesture-plan.json')):
        shutil.copyfile(path, root / name)
    entry = json.loads((root / 'catalog-entry.json').read_bytes())
    plan_sha = digest(root / 'gesture-plan.json')
    design = {'pairs': args.pairs, 'order': [['on', 'off'] if (i + (args.start_with == 'off')) % 2 == 0
                                          else ['off', 'on'] for i in range(args.pairs)],
              'gesturePlanSha256': plan_sha, 'appSha256': digest(root / 'app.apk'), 'testSha256': digest(root / 'test.apk'),
              'cacheOrPositionReset': False, 'contentReadinessWait': False, 'corpusCredit': 0,
              'rawMonotonicFtrace': args.raw_monotonic_ftrace,
              'scope': 'Measurement overhead comparison; initial cache/position equivalence and unavoidable-cost policy are not established.'}
    (root / 'design.json').write_text(json.dumps(design, indent=2), encoding='utf-8')
    result = {'designSha256': digest(root / 'design.json'), 'pairs': [], 'complete': False, 'performanceQualified': False, 'corpusCredit': 0}
    try:
        for index, order in enumerate(design['order'], 1):
            pair = {}
            for mode in order:
                name = f'pair-{index}-{mode}'
                command = [sys.executable, str(Path(__file__).with_name('collect_engine_live_trace.py')),
                           '--adb', args.adb, '--app-apk', str(root / 'app.apk'), '--test-apk', str(root / 'test.apk'),
                           '--output', str(root / name), '--catalog-ui', '--catalog-entry', str(root / 'catalog-entry.json'),
                           '--traverse-episode', '--gesture-plan', str(root / 'gesture-plan.json'),
                           '--source', entry['sourceId'], '--kind', entry['kind'], '--series-key', entry['seriesKey'],
                           '--episode-key', entry['episodeKey']]
                if mode == 'off':
                    command.append('--no-readback')
                if args.raw_monotonic_ftrace:
                    command.append('--raw-monotonic-ftrace')
                print(json.dumps({'started': name}), flush=True)
                with (root / (name + '.log')).open('wb') as log:
                    process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False)
                require(process.returncode == 0, 'failed control: ' + name)
                pair[mode] = measurements(root / name, plan_sha, mode == 'on')
                require(pair[mode]['appSha256'] == design['appSha256'] and pair[mode]['testSha256'] == design['testSha256'],
                        'APK changed during comparison')
                print(json.dumps({'completed': name, 'frames': pair[mode]['submittedFrameCount'],
                                  'p95Millis': pair[mode]['nativeSubmissionP95Millis']}), flush=True)
            require(pair['on']['gestureGeometrySha256'] == pair['off']['gestureGeometrySha256'], 'pair gesture geometry differs')
            pair['p95DifferenceMillis'] = pair['on']['nativeSubmissionP95Millis'] - pair['off']['nativeSubmissionP95Millis']
            result['pairs'].append(pair)
            (root / 'comparison.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        result['complete'] = True
    except (OSError, ValueError, KeyError, TypeError) as failure:
        result['error'] = str(failure)
    finally:
        (root / 'comparison.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({'complete': result['complete'], 'pairs': len(result['pairs']), 'error': result.get('error')}), flush=True)
    return 0 if result['complete'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
