"""Bounded native renderer experiment; raw output is diagnostic, never corpus credit."""
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
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--capture-python', type=Path)
    parser.add_argument('--generated-directory', type=Path)
    parser.add_argument('--discovery-file', type=Path)
    parser.add_argument('--geometry-mode', choices=['streaming', 'static'], default='streaming')
    args = parser.parse_args()
    adb = [args.adb, '-s', 'emulator-5554']
    if subprocess.check_output(adb + ['emu', 'avd', 'name'], text=True).splitlines()[0].strip() != 'MangaViewerApi35':
        raise RuntimeError('Wrong designated AVD')
    args.output.mkdir(parents=True, exist_ok=False)
    remote_root = '/sdcard/Android/data/ml.melun.mangaview/files/ux-evidence'
    before = set(subprocess.check_output(adb + ['shell', 'ls', '-1', remote_root], text=True).splitlines())
    run_id = 'probe-' + str(time.time_ns())
    remote = f'/data/misc/perfetto-traces/{run_id}.pftrace'
    config = Path(__file__).with_name('qualification_frames.cfg').read_bytes()
    clocks = []
    for _ in range(7):
        start = time.time_ns()
        guest = int(subprocess.check_output(adb + ['shell', 'date', '+%s%N'], text=True))
        clocks.append(dict(hostBeforeEpochNanos=start, guestEpochNanos=guest, hostAfterEpochNanos=time.time_ns()))
    args.output.joinpath('host-guest-clock.json').write_text(json.dumps(clocks, indent=2))
    package_path = subprocess.check_output(adb + ['shell', 'pm', 'path', 'ml.melun.mangaview'], text=True).strip().removeprefix('package:')
    if not re.fullmatch(r'/data/app/[A-Za-z0-9_./=+~\-]+\.apk', package_path):
        raise RuntimeError('Installed APK path unavailable')
    apk_hash = subprocess.check_output(adb + ['shell', 'sha256sum', package_path], text=True).split()[0]
    capture = None
    capture_log = args.output.joinpath('capture.log').open('wb')
    launch = subprocess.run(adb + ['shell', 'perfetto', '-c', '-', '--txt', '--background-wait', '-o', remote],
                            input=config, capture_output=True, check=True)
    args.output.joinpath('trace-start.txt').write_bytes(launch.stdout + launch.stderr)
    match = re.search(rb'(?m)^\s*(\d+)\s*$', launch.stdout + launch.stderr)
    if match is None:
        raise RuntimeError('Trace process PID unavailable')
    pid = match.group(1).decode('ascii')
    try:
        if args.capture_python:
            if not args.generated_directory or not args.discovery_file:
                raise RuntimeError('Capture requires explicit stubs and discovery file')
            capture = subprocess.Popen([str(args.capture_python.resolve()), '-B',
                str(Path(__file__).with_name('capture_emulator_display.py').resolve()),
                '--generated-directory', str(args.generated_directory.resolve()),
                '--discovery-file', str(args.discovery_file.resolve()), '--output', str((args.output / 'frames').resolve()),
                '--seconds', '22', '--width', '96', '--height', '208'], stdout=capture_log, stderr=subprocess.STDOUT)
        print('Started probe', args.output, args.geometry_mode, flush=True)
        instrument = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'ml.melun.mangaview.viewer.runtime.OwnedRendererCadenceTest', '-e', 'probeGeometryMode', args.geometry_mode,
            'ml.melun.mangaview.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, timeout=60)
        args.output.joinpath('cadence.txt').write_bytes(instrument.stdout + instrument.stderr)
        print((instrument.stdout + instrument.stderr).decode(errors='replace')[-1800:], flush=True)
        if capture:
            capture.wait(timeout=30)
    finally:
        if capture and capture.poll() is None:
            capture.terminate()
            capture.wait(timeout=10)
        capture_log.close()
        subprocess.run(adb + ['shell', 'kill', '-INT', pid], capture_output=True)
        deadline = time.monotonic() + 15
        alive = True
        while time.monotonic() < deadline:
            alive = subprocess.run(adb + ['shell', 'kill', '-0', pid], capture_output=True).returncode == 0
            if not alive:
                break
            time.sleep(.2)
        after = set(subprocess.check_output(adb + ['shell', 'ls', '-1', remote_root], text=True).splitlines())
        created = sorted(name for name in after - before if re.fullmatch(r'native-display-probe-\d+', name))
        pulls = []
        for source, target in [(remote, args.output / 'trace.pftrace')] + [(remote_root + '/' + name, args.output) for name in created]:
            result = subprocess.run(adb + ['pull', source, str(target)], capture_output=True)
            pulls.append(dict(source=source, exit=result.returncode, output=(result.stdout + result.stderr).decode(errors='replace')))
        args.output.joinpath('experiment.json').write_text(json.dumps(dict(
            mode='DIAGNOSTIC_NO_CORPUS_CREDIT', consecutivePassed=0, requestedGeometryMode=args.geometry_mode,
            installedApkSha256=apk_hash, traceFlushed=not alive, probeDirectories=created,
            captureExit=None if capture is None else capture.returncode, pulls=pulls,
            collectorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()), indent=2))
    if alive or len(created) != 1 or any(pull['exit'] for pull in pulls):
        raise RuntimeError('Experiment collection is incomplete')


if __name__ == '__main__':
    main()
