"""Collect a bounded exact-episode diagnostic. This command awards no corpus credit."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--series', required=True)
    parser.add_argument('--episode', required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--timeout-ms', type=int, default=60000)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.run_id):
        parser.error('run-id must contain only letters, numbers, underscores or hyphens')
    if not 1000 <= args.timeout_ms <= 300000:
        parser.error('timeout-ms must be between 1000 and 300000')
    adb = [args.adb, '-s', 'emulator-5554']
    avd = subprocess.check_output(adb + ['emu', 'avd', 'name'], text=True).splitlines()[0]
    if avd.strip() != 'MangaViewerApi35':
        raise RuntimeError('Unexpected designated emulator AVD')
    args.output.mkdir(parents=True, exist_ok=False)
    package_path = subprocess.check_output(adb + ['shell', 'pm', 'path', 'ml.melun.mangaview'], text=True).strip().removeprefix('package:')
    if not re.fullmatch(r'/data/app/[A-Za-z0-9_./=+~\-]+\.apk', package_path):
        raise RuntimeError('Installed APK identity unavailable')
    apk_hash = subprocess.check_output(adb + ['shell', 'sha256sum', package_path], text=True).split()[0]
    args.output.joinpath('candidate.json').write_text(json.dumps({
        'mode': 'DIAGNOSTIC_NO_CORPUS_CREDIT', 'installedApkSha256': apk_hash,
        'collectorSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'traceConfigSha256': hashlib.sha256(Path(__file__).with_name('qualification_frames.cfg').read_bytes()).hexdigest(),
        'deviceFingerprint': subprocess.check_output(adb + ['shell', 'getprop', 'ro.build.fingerprint'], text=True).strip(),
        'source': args.source, 'series': args.series, 'episode': args.episode,
        'timeoutMillis': args.timeout_ms, 'cacheManipulation': 'NONE'}, indent=2))
    remote = f'/data/misc/perfetto-traces/diagnostic-{args.run_id}.pftrace'
    config = Path(__file__).with_name('qualification_frames.cfg').read_bytes()
    launch = subprocess.run(adb + ['shell', 'perfetto', '-c', '-', '--txt',
        '--background-wait', '-o', remote], input=config, capture_output=True, check=True)
    args.output.joinpath('trace-start.txt').write_bytes(launch.stdout + launch.stderr)
    match = re.search(rb'(?m)^\s*(\d+)\s*$', launch.stdout + launch.stderr)
    if match is None:
        raise RuntimeError('Trace process identity unavailable')
    trace_pid = match.group(1).decode('ascii')
    result = None
    trace_flushed = False
    with args.output.joinpath('logcat.txt').open('wb') as log:
        logger = subprocess.Popen(adb + ['logcat', '-v', 'threadtime', '-T', '1'],
                                  stdout=log, stderr=subprocess.STDOUT)
        try:
            print(f'Started {args.source} exact episode diagnostic {args.run_id}', flush=True)
            command = adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                'ml.melun.mangaview.viewer.ViewerQualificationDiagnosticTest',
                '-e', 'sourceId', args.source, '-e', 'seriesKey', args.series,
                '-e', 'episodeKey', args.episode, '-e', 'diagnosticRunId', args.run_id,
                '-e', 'diagnosticTimeoutMillis', str(args.timeout_ms),
                'ml.melun.mangaview.test/androidx.test.runner.AndroidJUnitRunner']
            result = subprocess.run(command, capture_output=True,
                                    timeout=args.timeout_ms / 1000 + 120)
            args.output.joinpath('instrumentation.txt').write_bytes(result.stdout + result.stderr)
            print((result.stdout + result.stderr).decode(errors='replace')[-6000:], flush=True)
        finally:
            logger.terminate()
            logger.wait(timeout=10)
            subprocess.run(adb + ['shell', 'kill', '-INT', trace_pid], capture_output=True)
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                if subprocess.run(adb + ['shell', 'kill', '-0', trace_pid],
                                  capture_output=True).returncode != 0:
                    trace_flushed = True
                    break
                time.sleep(.2)
            pull_results = []
            for source, target in [(remote, args.output / 'trace.pftrace'),
                    (f'/sdcard/Android/data/ml.melun.mangaview/files/ux-evidence/diagnostic-{args.run_id}',
                     args.output)]:
                pull = subprocess.run(adb + ['pull', source, str(target)], capture_output=True)
                pull_results.append({'source': source, 'exit': pull.returncode,
                    'output': (pull.stdout + pull.stderr).decode(errors='replace')})
            args.output.joinpath('collection-status.json').write_text(json.dumps({
                'mode': 'DIAGNOSTIC_NO_CORPUS_CREDIT', 'consecutivePassed': 0,
                'traceFlushed': trace_flushed, 'instrumentationExit':
                None if result is None else result.returncode, 'pulls': pull_results}, indent=2))
    if result is None or result.returncode != 0 or not trace_flushed:
        return 1
    output = (result.stdout + result.stderr).decode(errors='replace')
    return 0 if re.search(r'OK \(\d+ tests?\)', output) and 'FAILURES!!!' not in output else 1


if __name__ == '__main__':
    raise SystemExit(main())
