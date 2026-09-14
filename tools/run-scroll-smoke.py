#!/usr/bin/env python3
"""Fast single-run dev smoke for the engine scroll qualification loop.

Purpose: shorten the fix -> measure iteration from ~60-80 min (full 3-run
qualification) to a single scoped capture with the bulk cache host. It does not
produce qualification receipts and deliberately skips the proof-only work
(full-cache backup, unrelated-entry proofs, final device restoration). It does
keep the parts that define what is measured: the verified scope plan, the
scoped cache isolation (so the capture is case-cold), prepare_p0_database, the
identical collector invocation, and the identical extractor/analyzer pipeline.

Layout:
  .artifacts/scroll-performance-20260912/smoke-<label>/
      baseline.json, original/, database/, original-cache/, cache-baseline.json,
      scope-manifest.json, run-<n>/{collection,trace,...}

First invocation bootstraps (baseline backup + cache backup + scope derive).
Later invocations reuse them and only restore the cache baseline, isolate the
scope, capture, and analyze. The pinned APKs stay installed between runs.
"""

from __future__ import annotations

import argparse
import json
import runpy
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
sys.path.insert(0, str(HERE))
OUT = REPO / ".artifacts/scroll-performance-20260912"
WRAPPER = OUT / "run-engine-scroll-qualification.py"
W = runpy.run_path(str(WRAPPER))

import qualify_engine_scroll_cold_fast as fast  # noqa: E402

BASE = W["BASE"]
COLLECTOR = W["COLLECTOR"]
LOCATOR = W["LOCATOR"]
EXTRACTOR = W["EXTRACTOR"]
ANALYZER = W["ANALYZER"]
CHECKER = W["CHECKER"]
DEVICE_FREE_FLOOR_KB = W["DEVICE_FREE_FLOOR_KB"]


class Phase:
    def __init__(self):
        self.start = time.time()
        self.last = self.start

    def mark(self, name: str) -> None:
        now = time.time()
        print(f"  [{name}] {now - self.last:6.1f}s (total {now - self.start:6.1f}s)", flush=True)
        self.last = now


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def sha256_file(path: Path) -> str:
    return W["sha256_file"](path)


def device_installed_sha(device, package: str) -> str | None:
    result = device.run("shell", "pm", "path", package)
    paths = [line.removeprefix("package:") for line in
             result.stdout.decode(errors="replace").splitlines() if line.startswith("package:")]
    if len(paths) != 1:
        return None
    digest = device.run("shell", "sha256sum", paths[0])
    fields = digest.stdout.decode(errors="replace").split()
    return fields[0].lower() if fields else None


def ensure_installed(device, package: str, apk: Path, expected: str) -> str:
    current = device_installed_sha(device, package)
    if current == expected:
        return "already-installed"
    device.install_and_verify(package, apk, expected)
    return "installed"


def ensure_bootstrap(smoke: Path, device, arguments, case: dict) -> tuple[dict, dict, dict, object]:
    baseline_path = smoke / "baseline.json"
    host = fast.DeviceCacheHost(device, BASE["PACKAGE"], spool_dir=OUT)
    if not baseline_path.is_file():
        print("[bootstrap] baseline backup (APKs + database + preferences)", flush=True)
        baseline = W["backup_baseline"](device, smoke)
    else:
        baseline = json.loads(baseline_path.read_bytes())
    cache_backup = smoke / "original-cache"
    cache_baseline_path = smoke / "cache-baseline.json"
    if not cache_baseline_path.is_file():
        print("[bootstrap] cache baseline backup (bulk tar)", flush=True)
        baseline_cache = fast.backup_cache(host, cache_backup)
        write_json(cache_baseline_path, {
            "roots": baseline_cache["roots"],
            "inventorySha256": baseline_cache["inventorySha256"],
            "cacheBaselineSha256": baseline_cache["cacheBaselineSha256"],
        })
    else:
        baseline_cache = json.loads(cache_baseline_path.read_bytes())
    scope_path = smoke / "scope-manifest.json"
    if not scope_path.is_file():
        print("[bootstrap] scope derivation", flush=True)
        plans = W["load_scope_plans"](arguments.scope_plans)
        database_rows = fast.read_database_rows(W["scope_read_copy"](smoke, baseline["database"]))
        scope = fast.derive_scope(
            {"sourceId": case["sourceId"], "seriesKey": case["seriesKey"],
             "episodeKey": case["episodeKey"]},
            plans, database_rows=database_rows, bindings=[])
        write_json(scope_path, scope)
    else:
        scope = json.loads(scope_path.read_bytes())
    return baseline, baseline_cache, scope, host


def run_iteration(smoke: Path, device, host, arguments, case: dict, baseline: dict,
                  baseline_cache: dict, scope: dict) -> int:
    existing = [p.name for p in smoke.glob("run-*") if p.is_dir()]
    index = 1 + max((int(name.split("-", 1)[1]) for name in existing
                     if name.split("-", 1)[1].isdigit()), default=0)
    run = smoke / f"run-{index}"
    run.mkdir()
    phase = Phase()
    print(f"[iteration {index}] {run}", flush=True)

    free_kb = W["device_free_kb"](arguments.adb)
    if free_kb is not None and free_kb < DEVICE_FREE_FLOOR_KB:
        raise SystemExit(f"device /data has only {free_kb} KiB free")
    app_sha = sha256_file(arguments.app_apk)
    test_sha = sha256_file(arguments.test_apk)
    app_state = ensure_installed(device, BASE["PACKAGE"], arguments.app_apk, app_sha)
    test_state = ensure_installed(device, BASE["TEST_PACKAGE"], arguments.test_apk, test_sha)
    phase.mark(f"install {app_state}/{test_state}")

    device.force_stopped()
    time.sleep(8)
    receipt = fast.restore_cache_baseline(host, smoke / "original-cache", baseline_cache)
    write_json(run / "cache-restore-before.json", receipt)
    phase.mark("cache-restore")

    BASE["prepare_p0_database"](device, smoke / "database", baseline["database"], case, 1)
    prepared = smoke / "database" / "case-01-working" / Path(BASE["DB"]).name
    write_json(run / "entry-state.json", {
        "databaseRestored": True, "prepareP0Applied": True,
        "preparedDatabaseSha256": sha256_file(prepared), "bookmarksPreserved": True,
        "method": "prepare_p0_database (selected series only)",
    })
    phase.mark("prepare-p0")

    isolation = fast.isolate_scope(host, scope)
    write_json(run / "scope-isolation.json", isolation)
    before_scoped = fast.scope_inventory(host, scope)
    write_json(run / "scope-inventory-before.json", before_scoped)
    if not before_scoped["scopedEntriesAbsent"]:
        raise RuntimeError(f"scoped entries remained before {run.name}")
    phase.mark(f"isolate-scope ({isolation['deletedCount']} deleted)")

    collection = run / "collection"
    exit_code = subprocess.call([
        sys.executable, str(COLLECTOR),
        "--adb", arguments.adb,
        "--output", str(collection),
        "--apk", str(arguments.app_apk),
        "--test-apk", str(arguments.test_apk),
        "--capture-source", case["sourceId"],
        "--capture-series", case["seriesKey"],
        "--capture-episode", case["episodeKey"],
        "--capture-kind", case["kind"],
        "--catalog-ui", "true",
        "--cross-next-boundary", arguments.cross_next_boundary,
        "--trace-config", str(arguments.trace_config),
    ], cwd=str(REPO))
    phase.mark("collector")
    if exit_code != 0:
        raise RuntimeError(f"collector failed ({exit_code}); see {collection}")
    for name in ("collection.json", "cache-inventory.json",
                 "cache-inventory-before.json", "cache-inventory-after.json"):
        if (collection / name).is_file():
            shutil.copy2(collection / name, run / name)
    W["reclaim_device_run_artifacts"](arguments.adb, run, collection)
    record = json.loads((run / "collection.json").read_bytes())
    W["mirror_capture"](run, Path(record["captureTarget"]).resolve())
    phase.mark("mirror")

    if arguments.skip_offline:
        print(json.dumps({"run": str(run), "capture": "ok", "offline": "skipped"}, indent=2), flush=True)
        return 0

    trace = run / "collection" / "trace.pftrace"
    binding = run / "input-binding.json"
    mirror = run / "trace" / "engine-capture-scroll-qualification"
    if trace.is_file():
        loss = run / "packet-loss-location.json"
        W["run_python"](LOCATOR, ["--trace", str(trace), "--out", str(loss)], run / "locate-loss.log")
        extract_args = ["--capture", str(mirror), "--trace", str(trace), "--output", str(binding)]
        W["run_python"](EXTRACTOR, extract_args, run / "extract.log")
    phase.mark("extract")
    analyzer_args = ["--run", str(run), "--output", str(run / "analysis.json")]
    if binding.is_file():
        analyzer_args += ["--input-binding", str(binding)]
    W["run_python"](ANALYZER, analyzer_args, run / "analysis.log")
    phase.mark("analyze")
    W["run_python"](CHECKER, ["--run", str(run), "--output", str(run / "policy.json")],
                    run / "policy.log")
    phase.mark("policy")

    policy = json.loads((run / "policy.json").read_bytes())
    viewer = json.loads((run / "analysis.json").read_bytes())["runs"][0]["viewer"]
    cadence = viewer["stages"]["afterFirstComplete"]["displayCadence"]
    work = viewer["stages"]["afterFirstComplete"]["work"]
    latency = viewer["stages"]["afterFirstComplete"]["appEventLatency"]
    first = viewer["firstFullViewportDisplay"]
    summary = {
        "runState": policy["runs"][0]["state"],
        "movingDisplayFrames": cadence["movingDisplayFrames"],
        "missedFrameRatio": cadence["missedFrameRatio"],
        "lowerBoundMissedFrameRatio": cadence["lowerBoundMissedFrameRatio"],
        "frameWorkGatingP95Ms": work["gatingP95Ms"],
        "appScrollEventToDisplayP95Ms": latency["appScrollEventToDisplayP95Ms"],
        "wholeCohortEventToDisplayP95Ms": latency["wholeCohortEventToDisplayP95Ms"],
        "firstFullViewportDisplay": first,
    }
    print(json.dumps(summary, indent=2, ensure_ascii=False), flush=True)
    states = [f"  {c['criterion']}: {c['state']}" for c in policy["runs"][0]["criteria"]]
    print("\n".join(states), flush=True)
    return 0 if policy["runs"][0]["state"] != "FAIL" else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", required=True)
    parser.add_argument("--case", required=True, type=int)
    parser.add_argument("--app-apk", required=True, type=Path)
    parser.add_argument("--test-apk", required=True, type=Path)
    parser.add_argument("--adb", default=W["DEFAULT_ADB"])
    parser.add_argument("--trace-config", type=Path, default=OUT / "engine-presentation-qualification-v2.cfg")
    parser.add_argument("--scope-plans", required=True, type=Path)
    parser.add_argument("--skip-offline", action="store_true")
    parser.add_argument("--cross-next-boundary", choices=("true", "false"), default="true")
    arguments = parser.parse_args(argv)
    if not W["LABEL_PATTERN"].fullmatch(arguments.label):
        parser.error(f"invalid label: {arguments.label!r}")
    if not arguments.trace_config.is_file():
        parser.error(f"trace config not found: {arguments.trace_config}")
    case = W["CASES"][arguments.case - 1]
    smoke = OUT / f"smoke-{arguments.label}"
    smoke.mkdir(parents=True, exist_ok=True)
    device = W["Device"](arguments.adb)
    device.validate()
    baseline, baseline_cache, scope, host = ensure_bootstrap(smoke, device, arguments, case)
    return run_iteration(smoke, device, host, arguments, case, baseline, baseline_cache, scope)


if __name__ == "__main__":
    raise SystemExit(main())
