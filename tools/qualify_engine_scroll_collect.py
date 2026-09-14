#!/usr/bin/env python3
"""Collect one installed-app staged engine-scroll qualification capture.

Runs `ml.melun.mangaview.viewer.EngineScrollQualificationTest` against the already
installed APKs, pulls exactly the new capture directory, records the fixed-12 case
coordinates, and records the app page-cache inventory before and after the run with
per-entry size/content-hash metadata. Nothing clears device state.

Trace capture is explicit: pass --trace-config to wrap the run in a perfetto trace.
The config is copied into the collection output and hashed before instrumentation
starts; when a trace is requested, collection success requires the trace to be
flushed, pulled, and hash-recorded. A trace is pulled even when instrumentation
fails, and a best-effort capture pull is attempted as well.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from collect_engine_readback_fixture import (  # noqa: E402
    CollectionError,
    INSTRUMENTATION_OK,
    PACKAGE,
    TEST_PACKAGE,
    _adb_checked,
    _adb_command,
    _collect_logcat,
    _decode,
    _device_snapshot,
    _installed_apk,
    _pull,
    _require_file,
    _run,
    _sha256_file,
    _start_trace,
    _stop_trace,
    _validate_device,
)

INSTRUMENTATION_CLASS = "ml.melun.mangaview.viewer.EngineScrollQualificationTest"
REMOTE_FILES_ROOT = f"/sdcard/Android/data/{PACKAGE}/files"
CAPTURE_PREFIX = "engine-scroll-qualification-"
CACHE_REMOTE = "app_engine_pages_v1/pages"


def _captures(adb: str) -> set[str]:
    result = _run(_adb_command(adb, "shell", "ls", "-1", REMOTE_FILES_ROOT))
    if result.returncode != 0:
        detail = (_decode(result.stdout) + _decode(result.stderr)).lower()
        if "no such file" in detail:
            return set()
        raise CollectionError(f"cannot list capture root: {detail[-1200:]}")
    return {
        line.strip() for line in _decode(result.stdout).splitlines()
        if line.strip().startswith(CAPTURE_PREFIX)
    }


def _inventory_digest(entries: list[dict]) -> str:
    canonical = json.dumps(sorted(entries, key=lambda item: item["name"]),
                           sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _cache_metadata(adb: str, names: list[str]) -> list[dict]:
    entries = {name: {"name": name, "sizeBytes": None, "sha256": None} for name in names}
    if not names:
        return []
    # The remote command must be one shell-quoted argument: adb joins argv with plain
    # spaces, so an unquoted `sh -c "cd X && wc -c *"` lets the device shell split on &&
    # and expand the glob in its own cwd instead of the cache directory.
    sizes = _run(_adb_command(adb, "shell",
                              f"run-as {PACKAGE} sh -c 'cd {CACHE_REMOTE} && wc -c *'"))
    if sizes.returncode == 0:
        for line in _decode(sizes.stdout).splitlines():
            parts = line.split()
            if len(parts) == 2 and parts[0].isdigit() and parts[1] in entries:
                entries[parts[1]]["sizeBytes"] = int(parts[0])
    hashes = _run(_adb_command(adb, "shell",
                               f"run-as {PACKAGE} sh -c 'cd {CACHE_REMOTE} && sha256sum *'"))
    if hashes.returncode == 0:
        for line in _decode(hashes.stdout).splitlines():
            parts = line.split()
            if len(parts) == 2 and len(parts[0]) == 64 and parts[1] in entries:
                entries[parts[1]]["sha256"] = parts[0].lower()
    return [entries[name] for name in names]


def _cache_inventory(adb: str) -> dict:
    result = _run(_adb_command(adb, "shell", "run-as", PACKAGE, "ls", "-1", CACHE_REMOTE))
    if result.returncode != 0:
        detail = (_decode(result.stdout) + _decode(result.stderr)).strip()
        return {
            "available": False,
            "reason": detail[-600:] or "run-as unavailable",
            "deviceCacheGloballyCleared": False,
            "pageCacheNames": [],
            "entries": [],
            "contentHashesAvailable": False,
            "inventorySha256": None,
            "count": 0,
        }
    names = sorted(line.strip() for line in _decode(result.stdout).splitlines() if line.strip())
    if len(names) != len(set(names)) or any("/" in name or name in {".", ".."} for name in names):
        raise CollectionError("page cache inventory contains ambiguous names")
    entries = _cache_metadata(adb, names)
    return {
        "available": True,
        "pageCacheNames": names,
        "entries": entries,
        "count": len(names),
        "contentHashesAvailable": bool(entries) and all(entry["sha256"] for entry in entries),
        "inventorySha256": _inventory_digest(entries),
        "deviceCacheGloballyCleared": False,
        "recordedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }


def _write_inventory(output: Path, name: str, inventory: dict) -> None:
    (output / name).write_text(json.dumps(inventory, indent=2, sort_keys=True) + "\n",
                               encoding="utf-8")


def _instrument(adb: str, output: Path, instrumentation_args: dict[str, str]) -> tuple[int | None, bool]:
    command = _adb_command(
        adb, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", INSTRUMENTATION_CLASS,
    )
    for name, value in instrumentation_args.items():
        command += ["-e", name, value]
    command.append(f"{TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner")
    try:
        result = subprocess.run(command, capture_output=True, timeout=1800, check=False)
        stdout, stderr, exit_code = result.stdout, result.stderr, result.returncode
    except subprocess.TimeoutExpired as error:
        stdout = error.stdout or b""
        stderr = error.stderr or b""
        exit_code = None
    combined = stdout + stderr
    output.joinpath("instrumentation.stdout.txt").write_bytes(stdout)
    output.joinpath("instrumentation.stderr.txt").write_bytes(stderr)
    output.joinpath("instrumentation.txt").write_bytes(combined)
    text = _decode(combined)
    a_match = INSTRUMENTATION_OK.search(text)
    success = (
        exit_code == 0 and a_match is not None and int(a_match.group(1)) == 1
        and "FAILURES!!!" not in text and "INSTRUMENTATION_FAILED" not in text
    )
    return exit_code, success


def _pull_capture(adb: str, output: Path, remote: str, name: str) -> dict:
    pull = _pull(adb, remote, output / name)
    return pull


def collect(adb: str, output: Path, apk: Path, test_apk: Path,
            instrumentation_args: dict[str, str], trace_config: Path | None) -> tuple[int, dict]:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.mkdir(exist_ok=False)
    report: dict = {
        "classification": "QUALIFICATION_PENDING_HOST_ANALYSIS",
        "physicalPresentationVerified": False,
        "collectorSha256": _sha256_file(Path(__file__).resolve()),
        "instrumentationClass": INSTRUMENTATION_CLASS,
        "instrumentationSuccess": False,
        "traceRequested": trace_config is not None,
        "traceSha256": None,
        "pulls": [],
        "cacheInventory": None,
    }
    trace_pid: str | None = None
    trace_remote: str | None = None
    before: set[str] | None = None
    try:
        avd = _validate_device(adb)
        local_hash = _sha256_file(apk)
        local_test_hash = _sha256_file(test_apk)
        target = _installed_apk(adb, PACKAGE, local_hash)
        test_target = _installed_apk(adb, TEST_PACKAGE, local_test_hash)
        report.update({
            "serial": "emulator-5554",
            "avd": avd,
            "localApkSha256": local_hash,
            "localTestApkSha256": local_test_hash,
            "installedApkSha256": target["sha256"],
            "installedTestApkSha256": test_target["sha256"],
            "device": _device_snapshot(adb),
            "bootId": _adb_checked(adb, "shell", "cat", "/proc/sys/kernel/random/boot_id").strip(),
        })
        before = _captures(adb)
        report["capturesBefore"] = sorted(before)
        report["cacheInventoryBefore"] = _cache_inventory(adb)
        _write_inventory(output, "cache-inventory-before.json", report["cacheInventoryBefore"])
        if trace_config is not None:
            run_name = f"engine-scroll-{time.time_ns()}"
            trace_remote = f"/data/misc/perfetto-traces/{run_name}.pftrace"
            report["traceRemotePath"] = trace_remote
            config_bytes = trace_config.read_bytes()
            report["traceConfigSha256"] = hashlib.sha256(config_bytes).hexdigest()
            report["traceConfigSource"] = str(trace_config.resolve())
            output.joinpath("trace.cfg").write_bytes(config_bytes)
            trace_pid = _start_trace(adb, trace_remote, config_bytes, output)
            report["tracePid"] = trace_pid
            report["traceStartBeforeInstrumentation"] = True
        exit_code, success = _instrument(adb, output, instrumentation_args)
        report["instrumentationExitCode"] = exit_code
        report["instrumentationSuccess"] = success
        if not success:
            raise CollectionError(
                f"instrumentation failed (exit={exit_code}); see {output / 'instrumentation.txt'}")
        after = _captures(adb)
        report["capturesAfter"] = sorted(after)
        created = sorted(after - before)
        if len(created) != 1:
            raise CollectionError(f"expected exactly one new capture directory, found {created}")
        report["captureRemote"] = f"{REMOTE_FILES_ROOT}/{created[0]}"
        pull = _pull(adb, report["captureRemote"], output / created[0])
        report["pulls"].append(pull)
        if pull["exit"] != 0:
            raise CollectionError(f"capture pull failed: {pull['output'][-1200:]}")
        report["captureTarget"] = str((output / created[0]).resolve())
    except (CollectionError, OSError, subprocess.TimeoutExpired) as error:
        report["error"] = str(error)
    finally:
        if trace_pid is not None:
            try:
                report["traceFlushed"] = _stop_trace(adb, trace_pid)
            except CollectionError as error:
                report["collectionError"] = str(error)
                report["traceFlushed"] = False
        if trace_remote and report.get("traceFlushed") is True:
            try:
                trace_pull = _pull_capture(adb, output, trace_remote, "trace.pftrace")
                report["pulls"].append(trace_pull)
                report["tracePullExit"] = trace_pull["exit"]
                if trace_pull["exit"] == 0 and (output / "trace.pftrace").is_file():
                    report["traceSha256"] = _sha256_file(output / "trace.pftrace")
            except (CollectionError, OSError) as error:
                report["tracePullError"] = str(error)
                report["tracePullExit"] = 1
        if report.get("captureTarget") is None and before is not None:
            try:
                after = _captures(adb)
                created = sorted(after - before)
                if len(created) == 1:
                    remote = f"{REMOTE_FILES_ROOT}/{created[0]}"
                    pull = _pull(adb, remote, output / created[0])
                    report["pulls"].append(pull)
                    report["capturesAfter"] = sorted(after)
                    if pull["exit"] == 0:
                        report["captureTarget"] = str((output / created[0]).resolve())
                        report["partialCapture"] = True
            except (CollectionError, OSError) as error:
                report["partialCaptureError"] = str(error)
        try:
            report["logcatExit"] = _collect_logcat(adb, output)
        except CollectionError as error:
            report["collectionError"] = str(error)
            report["logcatExit"] = 1
        try:
            report["cacheInventoryAfter"] = _cache_inventory(adb)
            _write_inventory(output, "cache-inventory-after.json", report["cacheInventoryAfter"])
            _write_inventory(output, "cache-inventory.json", report["cacheInventoryAfter"])
            report["cacheInventory"] = report["cacheInventoryAfter"]
        except (CollectionError, OSError) as error:
            report["cacheInventoryError"] = str(error)
        trace_ok = (not report["traceRequested"]) or (
            report.get("traceFlushed") is True
            and report.get("tracePullExit") == 0
            and bool(report.get("traceSha256")))
        report["traceFailClosedOk"] = trace_ok
        report["success"] = bool(
            report.get("instrumentationSuccess") and report.get("captureTarget")
            and report.get("cacheInventoryBefore") and report.get("cacheInventoryAfter")
            and report.get("logcatExit") == 0 and trace_ok
        )
        output.joinpath("collection.json").write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return (0 if report.get("success") else 1), report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--test-apk", required=True, type=Path)
    parser.add_argument("--capture-source", default="wfwf")
    parser.add_argument("--capture-series", default="comic:10001")
    parser.add_argument("--capture-episode", default="1")
    parser.add_argument("--capture-kind", default="COMIC")
    parser.add_argument("--catalog-ui", choices=("true", "false"), default="true")
    parser.add_argument("--cross-next-boundary", choices=("true", "false"), default="true")
    parser.add_argument("--trace-config", type=Path)
    arguments = parser.parse_args(argv)
    try:
        adb = str(_require_file(arguments.adb, "adb executable"))
        apk = _require_file(arguments.apk, "target APK")
        test_apk = _require_file(arguments.test_apk, "test APK")
        trace_config = _require_file(arguments.trace_config, "trace config") \
            if arguments.trace_config else None
        instrumentation_args = {
            "catalogUi": arguments.catalog_ui,
            "captureCrossNextBoundary": arguments.cross_next_boundary,
            "captureSource": arguments.capture_source,
            "captureSeries": arguments.capture_series,
            "captureEpisode": arguments.capture_episode,
            "captureKind": arguments.capture_kind,
        }
        exit_code, report = collect(
            adb, arguments.output, apk, test_apk, instrumentation_args, trace_config)
    except (CollectionError, OSError) as error:
        print(f"collection failed: {error}", file=sys.stderr)
        return 1
    print(f"capture: {report.get('captureTarget')}")
    print(f"success: {str(bool(report.get('success'))).lower()}")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
