"""Collect one installed-app engine readback fixture without installing or clearing state."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


SERIAL = "emulator-5554"
EXPECTED_AVD = "MangaViewerApi35"
PACKAGE = "ml.melun.mangaview"
TEST_PACKAGE = "ml.melun.mangaview.test"
REMOTE_FIXTURE_ROOT = "/sdcard/Android/data/ml.melun.mangaview/files/engine-readback-fixtures"
INSTRUMENTATION_CLASS = (
    "ml.melun.mangaview.viewer.runtime.EngineReadbackContractTest,"
    "ml.melun.mangaview.viewer.runtime.EngineReadbackInstrumentedTest"
)
TRACE_CONFIG = Path(__file__).with_name("engine_readback_frames.cfg")
RUN_NAME = re.compile(r"^run-[0-9]+$")
APK_PATH = re.compile(r"^/data/app/[A-Za-z0-9_./=+~\-]+\.apk$")
SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
INSTRUMENTATION_OK = re.compile(r"\bOK \((\d+) tests?\)")


class CollectionError(RuntimeError):
    pass


def _decode(value: bytes) -> str:
    return value.decode("utf-8", errors="replace")


def _run(command: list[str], *, input_bytes: bytes | None = None, timeout: float | None = None) -> subprocess.CompletedProcess[bytes]:
    try:
        return subprocess.run(command, input=input_bytes, capture_output=True, timeout=timeout, check=False)
    except OSError as error:
        raise CollectionError(f"command could not start: {command[0]}: {error}") from error


def _adb_command(adb: str, *arguments: str) -> list[str]:
    return [adb, "-s", SERIAL, *arguments]


def _adb_checked(adb: str, *arguments: str) -> str:
    result = _run(_adb_command(adb, *arguments))
    if result.returncode != 0:
        detail = (_decode(result.stdout) + _decode(result.stderr)).strip()
        raise CollectionError(f"adb {' '.join(arguments[:2])} failed: {detail[-1200:]}")
    return _decode(result.stdout)


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as error:
        raise CollectionError(f"cannot hash {path}: {error}") from error
    return digest.hexdigest()


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _require_file(path: Path, label: str) -> Path:
    if not path.is_file():
        raise CollectionError(f"{label} is not a regular file: {path}")
    return path.resolve()


def _validate_device(adb: str) -> str:
    result = _run([adb, "devices", "-l"])
    if result.returncode != 0:
        raise CollectionError(f"adb devices failed: {_decode(result.stderr).strip()[-1200:]}")
    records: list[tuple[str, str]] = []
    for line in _decode(result.stdout).splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[1] in {"device", "offline", "unauthorized", "bootloader"}:
            records.append((fields[0], fields[1]))
    if records != [(SERIAL, "device")]:
        raise CollectionError(f"expected only online designated device {SERIAL}, found {records}")
    avd_lines = _adb_checked(adb, "emu", "avd", "name").splitlines()
    avd = next((line.strip() for line in avd_lines if line.strip() and line.strip() != "OK"), "")
    if avd != EXPECTED_AVD:
        raise CollectionError(f"wrong designated AVD: expected {EXPECTED_AVD}, got {avd!r}")
    return avd


def _safe_installed_path(value: str, package: str) -> str:
    paths = []
    for line in value.splitlines():
        line = line.strip()
        if line.startswith("package:"):
            paths.append(line[len("package:"):])
    if len(paths) != 1:
        raise CollectionError(f"{package} pm path did not return exactly one APK: {paths}")
    path = paths[0]
    if not APK_PATH.fullmatch(path) or ".." in Path(path).parts:
        raise CollectionError(f"{package} returned an unsafe APK path: {path!r}")
    return path


def _installed_apk(adb: str, package: str, local_hash: str) -> dict[str, str]:
    remote_path = _safe_installed_path(_adb_checked(adb, "shell", "pm", "path", package), package)
    result = _run(_adb_command(adb, "shell", "sha256sum", remote_path))
    if result.returncode != 0:
        raise CollectionError(f"sha256sum failed for {package}: {_decode(result.stderr).strip()[-1200:]}")
    fields = _decode(result.stdout).strip().split()
    if len(fields) < 2 or not SHA256.fullmatch(fields[0]) or fields[1] != remote_path:
        raise CollectionError(f"sha256sum did not identify the exact installed APK for {package}")
    device_hash = fields[0].lower()
    if device_hash != local_hash:
        raise CollectionError(f"installed {package} hash does not match the supplied APK")
    return {"package": package, "path": remote_path, "sha256": device_hash}


def _fixture_directories(adb: str) -> set[str]:
    result = _run(_adb_command(adb, "shell", "ls", "-1", REMOTE_FIXTURE_ROOT))
    if result.returncode != 0:
        detail = (_decode(result.stdout) + _decode(result.stderr)).lower()
        if "no such file" in detail:
            return set()
        raise CollectionError(f"cannot list remote fixture directory: {detail[-1200:]}")
    names = {line.strip() for line in _decode(result.stdout).splitlines() if line.strip()}
    return {name for name in names if RUN_NAME.fullmatch(name)}


def _device_snapshot(adb: str) -> dict[str, str]:
    return {
        "buildFingerprint": _adb_checked(adb, "shell", "getprop", "ro.build.fingerprint").strip(),
        "uname": _adb_checked(adb, "shell", "uname", "-a").strip(),
        "sdk": _adb_checked(adb, "shell", "getprop", "ro.build.version.sdk").strip(),
        "packageUid": _adb_checked(adb, "shell", "cmd", "package", "list", "packages", "-U", PACKAGE),
        "meminfo": _adb_checked(adb, "shell", "cat", "/proc/meminfo"),
        "wmSize": _adb_checked(adb, "shell", "wm", "size"),
        "wmDensity": _adb_checked(adb, "shell", "wm", "density"),
    }


def _start_trace(adb: str, trace_remote: str, config: bytes, output: Path) -> str:
    result = _run(
        _adb_command(adb, "shell", "perfetto", "--txt", "--background-wait", "-c", "-", "-o", trace_remote),
        input_bytes=config,
        timeout=30,
    )
    trace_output = result.stdout + result.stderr
    output.joinpath("trace-start.txt").write_bytes(trace_output)
    if result.returncode != 0:
        raise CollectionError(f"perfetto did not start: {_decode(trace_output)[-1200:]}")
    pids = re.findall(rb"(?m)^\s*([0-9]+)\s*$", trace_output)
    if len(pids) != 1 or int(pids[0]) <= 0:
        raise CollectionError("perfetto did not return exactly one trace process PID")
    return pids[0].decode("ascii")


def _stop_trace(adb: str, pid: str) -> bool:
    _run(_adb_command(adb, "shell", "kill", "-INT", pid))
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline:
        probe = _run(_adb_command(adb, "shell", "kill", "-0", pid))
        if probe.returncode != 0:
            detail = (_decode(probe.stdout) + _decode(probe.stderr)).lower()
            if "no such process" in detail:
                return True
        time.sleep(0.2)
    return False


def _instrument(adb: str, output: Path) -> tuple[int | None, bytes, bool, int | None]:
    command = _adb_command(
        adb,
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        INSTRUMENTATION_CLASS,
        f"{TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner",
    )
    try:
        result = subprocess.run(command, capture_output=True, timeout=45, check=False)
        stdout = result.stdout
        stderr = result.stderr
        exit_code: int | None = result.returncode
    except subprocess.TimeoutExpired as error:
        stdout = error.stdout or b""
        stderr = error.stderr or b""
        exit_code = None
    combined = stdout + stderr
    output.joinpath("instrumentation.stdout.txt").write_bytes(stdout)
    output.joinpath("instrumentation.stderr.txt").write_bytes(stderr)
    output.joinpath("instrumentation.txt").write_bytes(combined)
    text = _decode(combined)
    match = INSTRUMENTATION_OK.search(text)
    test_count = int(match.group(1)) if match else None
    success = exit_code == 0 and test_count == 7 and "FAILURES!!!" not in text and "INSTRUMENTATION_FAILED" not in text
    return exit_code, combined, success, test_count


def _pull(adb: str, source: str, target: Path) -> dict[str, Any]:
    result = _run(_adb_command(adb, "pull", source, str(target)))
    return {
        "source": source,
        "target": str(target),
        "exit": result.returncode,
        "output": (_decode(result.stdout) + _decode(result.stderr))[-4000:],
    }


def _collect_logcat(adb: str, output: Path) -> int:
    result = _run(_adb_command(adb, "logcat", "-d", "-v", "threadtime", "-t", "500"))
    output.joinpath("logcat.txt").write_bytes(result.stdout + result.stderr)
    return result.returncode


def _base_report(output: Path, collector_hash: str, config_hash: str) -> dict[str, Any]:
    return {
        "classification": "FIXTURE_ONLY_NO_CORPUS_CREDIT",
        "producerLayerBindingVerified": False,
        "physicalPresentationVerified": False,
        "physicalPresentationTimeNanos": None,
        "collectorSha256": collector_hash,
        "traceConfigSha256": config_hash,
        "artifactDirectory": str(output.resolve()),
        "traceFlushed": False,
        "traceSha256": None,
        "instrumentationSuccess": False,
        "instrumentationTestCount": None,
        "createdFixtureDirectories": [],
        "pulls": [],
    }


def collect(adb: str, output: Path, apk: Path, test_apk: Path) -> tuple[int, dict[str, Any]]:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.mkdir(exist_ok=False)
    collector_hash = _sha256_file(Path(__file__).resolve())
    config_bytes = TRACE_CONFIG.read_bytes()
    config_hash = _sha256_bytes(config_bytes)
    report = _base_report(output, collector_hash, config_hash)
    trace_pid: str | None = None
    trace_remote: str | None = None
    before: set[str] | None = None
    after: set[str] = set()
    instrumentation_output = b""
    try:
        avd = _validate_device(adb)
        local_apk_hash = _sha256_file(apk)
        local_test_hash = _sha256_file(test_apk)
        target_device = _installed_apk(adb, PACKAGE, local_apk_hash)
        test_device = _installed_apk(adb, TEST_PACKAGE, local_test_hash)
        report.update({
            "serial": SERIAL,
            "avd": avd,
            "localApkSha256": local_apk_hash,
            "localTestApkSha256": local_test_hash,
            "installedApkSha256": target_device["sha256"],
            "installedTestApkSha256": test_device["sha256"],
            "deviceApkSha256": target_device["sha256"],
            "deviceTestApkSha256": test_device["sha256"],
            "deviceApk": target_device,
            "deviceTestApk": test_device,
            "device": _device_snapshot(adb),
        })
        before = _fixture_directories(adb)
        report["fixtureDirectoriesBefore"] = sorted(before)
        run_name = f"engine-readback-{time.time_ns()}"
        trace_remote = f"/data/misc/perfetto-traces/{run_name}.pftrace"
        report["traceRemotePath"] = trace_remote
        trace_pid = _start_trace(adb, trace_remote, config_bytes, output)
        report["tracePid"] = trace_pid
        exit_code, instrumentation_output, instrumentation_success, test_count = _instrument(adb, output)
        report.update({
            "instrumentationExitCode": exit_code,
            "instrumentationSuccess": instrumentation_success,
            "instrumentationTestCount": test_count,
            "instrumentationClass": INSTRUMENTATION_CLASS,
        })
        if not instrumentation_success:
            raise CollectionError("instrumentation did not report OK (N tests) without failures")
    except (CollectionError, OSError, subprocess.TimeoutExpired) as error:
        report["error"] = str(error)
    finally:
        if trace_pid is not None:
            try:
                report["traceFlushed"] = _stop_trace(adb, trace_pid)
            except CollectionError as error:
                report["collectionError"] = str(error)
                report["traceFlushed"] = False
        try:
            report["logcatExit"] = _collect_logcat(adb, output)
        except CollectionError as error:
            report["collectionError"] = str(error)
            report["logcatExit"] = 1
        if before is not None:
            try:
                after = _fixture_directories(adb)
                report["fixtureDirectoriesAfter"] = sorted(after)
                new_names = sorted(after - before)
                report["createdFixtureDirectories"] = new_names
                if trace_remote is not None and report["traceFlushed"]:
                    trace_pull = _pull(adb, trace_remote, output / "trace.pftrace")
                    report["pulls"].append(trace_pull)
                    if trace_pull["exit"] == 0 and (output / "trace.pftrace").is_file():
                        report["traceSha256"] = _sha256_file(output / "trace.pftrace")
                for name in new_names:
                    report["pulls"].append(_pull(
                        adb,
                        f"{REMOTE_FIXTURE_ROOT}/{name}",
                        output / name,
                    ))
            except (CollectionError, OSError) as error:
                report["collectionError"] = str(error)
        report["pullSuccess"] = bool(report["pulls"]) and all(item["exit"] == 0 for item in report["pulls"])
        report["singleNewFixtureDirectory"] = len(report["createdFixtureDirectories"]) == 1
        report["success"] = bool(
            report.get("instrumentationSuccess") and report.get("traceFlushed") and
            report.get("singleNewFixtureDirectory") and report.get("pullSuccess") and
            report.get("logcatExit") == 0 and report.get("traceSha256")
        )
        output.joinpath("collection.json").write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8",
        )
    return (0 if report.get("success") else 1), report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--test-apk", required=True, type=Path)
    arguments = parser.parse_args(argv)
    try:
        adb = str(_require_file(arguments.adb, "adb executable"))
        apk = _require_file(arguments.apk, "target APK")
        test_apk = _require_file(arguments.test_apk, "test APK")
        exit_code, report = collect(adb, arguments.output, apk, test_apk)
    except (CollectionError, OSError) as error:
        print(f"collection failed: {error}", file=sys.stderr)
        return 1
    print(f"artifact path: {report['artifactDirectory']}")
    print(f"success: {str(bool(report.get('success'))).lower()}")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
