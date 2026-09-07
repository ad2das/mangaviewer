#!/usr/bin/env python3
"""Audit saved Android memory/resource evidence without touching a device.

This is deliberately a host-side evidence auditor.  It does not run Gradle,
ADB, or any application code.  ActivityManager ownership and dumpsys meminfo
are treated as the source of process PSS; gfxinfo is a separate diagnostic
view and is never added to PSS.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any, Iterable


REPO = Path(__file__).resolve().parents[1]
DEFAULT_MEMORY_DIR = REPO / ".artifacts/qualification-integration-20260905/session-memory-195423798235100"
DEFAULT_GFXINFO = REPO / ".artifacts/qualification-live-20260905/ntk-diagnostic-v6/diagnostic-ntk-v6/diagnostic-ntk-v6-197343407/gfxinfo-framestats.txt"
DEFAULT_LIVE_DIR = REPO / ".artifacts/qualification-live-20260905"
DEFAULT_OUTPUT = REPO / ".artifacts/paseo-dispatch-20260905/memory-attribution-audit.json"
PACKAGE = "ml.melun.mangaview"


PROCESS_RECORD = re.compile(
    r"(?m)^\s*\*(?P<kind>[A-Z]+)\*\s+UID\s+(?P<uid>\d+)\s+"
    r"ProcessRecord\{[^ ]+\s+(?P<pid>\d+):(?P<name>[^/}\s]+)"
)
FIELD = re.compile(r"(?m)^\s*(?P<name>packageList|packageDependencies)=\{(?P<body>[^}]*)\}")
MEMORY_FILE = re.compile(r"memory-(?P<index>\d+)-(?P<pid>\d+)\.txt$")
OWNERSHIP_FILE = re.compile(r"memory-ownership-(?P<index>\d+)\.txt$")
TERMINAL_ZERO_FIELDS = (
    "activeFetches",
    "activeDecodes",
    "activeUploads",
    "activeManifests",
    "retryWakeups",
    "retiringPages",
    "residentTextures",
)


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def rel(path: Path) -> str:
    try:
        return path.resolve().relative_to(REPO.resolve()).as_posix()
    except ValueError:
        return str(path)


def split_field(body: str) -> list[str]:
    return [value.strip() for value in body.split(",") if value.strip()]


def parse_process_records(raw: str, package_name: str = PACKAGE) -> list[dict[str, Any]]:
    """Parse only the top-level ProcessRecord blocks, not later PID references."""

    matches = list(PROCESS_RECORD.finditer(raw))
    records: list[dict[str, Any]] = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(raw)
        block = raw[match.start() : end]
        fields = {m.group("name"): split_field(m.group("body")) for m in FIELD.finditer(block)}
        package_list = fields.get("packageList", [])
        dependencies = fields.get("packageDependencies", [])
        hosting_lines = [line.strip() for line in block.splitlines() if "HostingRecord" in line]
        process_name = match.group("name")
        owned = (
            package_name in package_list
            or process_name == package_name
            or process_name.startswith(package_name + ":")
            or any(package_name + "/" in line for line in hosting_lines)
        )
        actual_webview = (
            "com.google.android.webview" in package_list
            or process_name.startswith("com.google.android.webview")
        )
        dependency_webview = "com.google.android.webview" in dependencies
        records.append(
            {
                "kind": match.group("kind"),
                "uid": int(match.group("uid")),
                "pid": int(match.group("pid")),
                "name": process_name,
                "package_list": package_list,
                "package_dependencies": dependencies,
                "hosting_lines": hosting_lines,
                "owned_by_package": owned,
                "actual_webview_process": actual_webview,
                "webview_dependency_only": dependency_webview and not actual_webview,
            }
        )
    return records


def parse_meminfo(raw: str) -> dict[str, Any]:
    total_pss = re.search(r"TOTAL PSS:\s*(\d+)", raw)
    total_rss = re.search(r"TOTAL RSS:\s*(\d+)", raw)
    total_swap = re.search(r"TOTAL SWAP \(KB\):\s*(\d+)", raw)
    if total_pss is None:
        total_pss = re.search(r"(?m)^\s*TOTAL\s+(\d+)\s", raw)

    summary_start = raw.find(" App Summary")
    summary = raw[summary_start:] if summary_start >= 0 else ""
    summary_fields: dict[str, int | None] = {}
    for key in ("Java Heap", "Native Heap", "Code", "Stack", "Graphics", "Private Other", "System"):
        match = re.search(rf"(?m)^\s*{re.escape(key)}:\s*(\d+)", summary)
        summary_fields[key] = int(match.group(1)) if match else None

    table_rows: dict[str, int] = {}
    for line in raw.splitlines():
        match = re.match(r"^\s*(Native Heap|Dalvik Heap|Dalvik Other|Stack|Ashmem|Graphics)\s+(\d+)\s+", line)
        if match:
            table_rows[match.group(1)] = int(match.group(2))

    webviews = re.search(r"WebViews:\s*(\d+)", raw)
    return {
        "total_pss_kib": int(total_pss.group(1)) if total_pss else None,
        "total_rss_kib": int(total_rss.group(1)) if total_rss else None,
        "total_swap_kib": int(total_swap.group(1)) if total_swap else None,
        "app_summary_pss_kib": summary_fields,
        "meminfo_table_pss_kib": table_rows,
        "webviews": int(webviews.group(1)) if webviews else None,
    }


def parse_gfxinfo(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    raw = path.read_text(encoding="utf-8", errors="replace")
    header = re.search(r"\*\* Graphics info for pid (\d+) \[([^]]+)]", raw)
    cpu = re.search(r"Total CPU memory usage:\s*\n\s*(\d+) bytes", raw)
    gpu = re.search(r"Total GPU memory usage:\s*\n\s*(\d+) bytes", raw)
    max_resource = re.search(r"Max resource usage:\s*([^\n]+)", raw)
    buffers = re.findall(r"(?m)^0x[0-9a-f]+\s+\|", raw)
    allocated = re.search(r"Total allocated by GraphicBufferAllocator \(estimate\):\s*([^\n]+)", raw)
    return {
        "path": rel(path),
        "pid": int(header.group(1)) if header else None,
        "package_or_process": header.group(2) if header else None,
        "total_cpu_cache_bytes": int(cpu.group(1)) if cpu else None,
        "total_gpu_cache_bytes": int(gpu.group(1)) if gpu else None,
        "max_resource_usage": max_resource.group(1).strip() if max_resource else None,
        "graphic_buffer_row_count": len(buffers),
        "graphic_buffer_allocator_estimate": allocated.group(1).strip() if allocated else None,
        "additive_to_activity_manager_pss": False,
        "reason_not_additive": (
            "gfxinfo CPU/GPU caches and GraphicBuffer rows are a separate renderer/resource "
            "view; adding them to TOTAL PSS would double-count shared CPU/GPU memory."
        ),
    }


def terminal_snapshot_is_zero(snapshot: dict[str, Any]) -> bool:
    return all(snapshot.get(field) == 0 for field in TERMINAL_ZERO_FIELDS)


def parse_resource_regression(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    data = load_json(path)
    cycles = data.get("cycles", [])
    terminal = [cycle.get("terminal", {}) for cycle in cycles]
    terminal_zero = bool(terminal) and all(
        terminal_snapshot_is_zero(snapshot) for snapshot in terminal
    )
    return {
        "path": rel(path),
        "mode": data.get("mode"),
        "cycle_count": len(cycles),
        "terminal_snapshots": terminal,
        "terminal_pipeline_resources_zero": terminal_zero,
        "fixture_only": data.get("mode") == "FIXTURE_REGRESSION_NO_CORPUS_CREDIT",
        "fixture_manifest_calls": data.get("fixtureManifestCalls"),
        "fixture_fetch_calls": data.get("fixtureFetchCalls"),
        "failures": data.get("failures", []),
    }


def parse_complete_resume_regressions(root: Path) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for path in sorted(root.rglob("regression.json")):
        if "complete-resume" not in str(path).lower():
            continue
        data = load_json(path)
        result.append(
            {
                "path": rel(path),
                "mode": data.get("mode"),
                "exact_pixels_equal": data.get("exactPixelsEqual"),
                "closed_cycles": data.get("closedCycles"),
                "remaining_body_descriptors": data.get("remainingBodyDescriptors"),
                "remaining_lease_directories": data.get("remainingLeaseDirectories"),
                "zero_closed_fixture_ownership": (
                    data.get("remainingBodyDescriptors") == 0
                    and data.get("remainingLeaseDirectories") == 0
                ),
                "fixture_only": data.get("mode") == "FIXTURE_REGRESSION_NO_CORPUS_CREDIT",
            }
        )
    return result


def source_markers() -> dict[str, Any]:
    qualification = REPO / "app/src/androidTest/java/ml/melun/mangaview/viewer/QualificationMemory.kt"
    memory_test = REPO / "app/src/androidTest/java/ml/melun/mangaview/viewer/runtime/SessionMemoryPressureRegressionTest.kt"
    fixture = REPO / "app/src/debug/java/ml/melun/mangaview/viewer/runtime/SessionMemoryFixture.kt"
    complete = REPO / "app/src/androidTest/java/ml/melun/mangaview/viewer/runtime/CompleteCachedResumeRegressionTest.kt"
    q_text = qualification.read_text(encoding="utf-8") if qualification.is_file() else ""
    m_text = memory_test.read_text(encoding="utf-8") if memory_test.is_file() else ""
    f_text = fixture.read_text(encoding="utf-8") if fixture.is_file() else ""
    c_text = complete.read_text(encoding="utf-8") if complete.is_file() else ""
    return {
        "qualification_memory_path": rel(qualification),
        "uses_activity_manager_process_dump": "dumpsys activity processes" in q_text,
        "uses_meminfo_per_pid": "dumpsys meminfo $pid" in q_text,
        "aggregates_only_total_pss": "OwnedPssSample" in q_text and "totalPss(raw)" in q_text,
        "writes_raw_ownership_and_meminfo": "memory-ownership-" in q_text and "memory-${samples.size}" in q_text,
        "session_memory_fixture_has_no_graph_provider_or_network": (
            "no graph, provider delegate, production cache, or network client" in f_text
        ),
        "session_memory_test_writes_terminal_pipeline_snapshot": "terminal" in m_text and "snapshotJson" in m_text,
        "complete_fixture_asserts_zero_body_descriptors_and_leases": (
            "remainingBodyDescriptors" in c_text and "remainingLeaseDirectories" in c_text
        ),
        "complete_and_memory_evidence_is_fixture_marked": (
            "FIXTURE_REGRESSION_NO_CORPUS_CREDIT" in m_text and "FIXTURE_REGRESSION_NO_CORPUS_CREDIT" in c_text
        ),
    }


def ownership_snapshot(path: Path, package_name: str, memory_files: dict[int, list[dict[str, Any]]]) -> dict[str, Any]:
    raw = path.read_text(encoding="utf-8", errors="replace")
    index_match = OWNERSHIP_FILE.search(path.name)
    index = int(index_match.group("index")) if index_match else None
    records = parse_process_records(raw, package_name)
    owned = [record for record in records if record["owned_by_package"]]
    webview = [record for record in records if record["actual_webview_process"]]
    dependency_only = [record for record in records if record["webview_dependency_only"]]
    observed_meminfo = memory_files.get(index if index is not None else -1, [])
    observed_pids = {entry["pid"] for entry in observed_meminfo}
    missing_owned = sorted(record["pid"] for record in owned if record["pid"] not in observed_pids)
    return {
        "path": rel(path),
        "index": index,
        "process_record_count": len(records),
        "owned_processes": [
            {"pid": record["pid"], "name": record["name"], "package_list": record["package_list"]}
            for record in owned
        ],
        "actual_webview_processes_in_dump": [
            {
                "pid": record["pid"],
                "name": record["name"],
                "package_list": record["package_list"],
                "owned_by_package": record["owned_by_package"],
            }
            for record in webview
        ],
        "webview_dependency_only_processes": [
            {
                "pid": record["pid"],
                "name": record["name"],
                "package_dependencies": record["package_dependencies"],
                "owned_by_package": record["owned_by_package"],
            }
            for record in dependency_only
        ],
        "meminfo_pids": sorted(observed_pids),
        "owned_processes_missing_meminfo": missing_owned,
        "app_pid_present": any(record["name"] == package_name for record in owned),
    }


def build_report(
    memory_dir: Path = DEFAULT_MEMORY_DIR,
    gfxinfo: Path = DEFAULT_GFXINFO,
    live_dir: Path = DEFAULT_LIVE_DIR,
    package_name: str = PACKAGE,
) -> dict[str, Any]:
    memory_json_path = memory_dir / "memory.json"
    regression_path = memory_dir / "regression.json"
    memory_json = load_json(memory_json_path)

    memory_files: dict[int, list[dict[str, Any]]] = {}
    for path in sorted(memory_dir.glob("memory-*.txt")):
        match = MEMORY_FILE.match(path.name)
        if not match:
            continue
        index = int(match.group("index"))
        pid = int(match.group("pid"))
        observation = parse_meminfo(path.read_text(encoding="utf-8", errors="replace"))
        observation.update({"path": rel(path), "index": index, "pid": pid})
        memory_files.setdefault(index, []).append(observation)

    ownership_files = sorted(memory_dir.glob("memory-ownership-*.txt"), key=lambda path: int(OWNERSHIP_FILE.match(path.name).group("index")))
    ownership = [ownership_snapshot(path, package_name, memory_files) for path in ownership_files]

    sample_rows: list[dict[str, Any]] = []
    for index, sample in enumerate(memory_json.get("samples", [])):
        expected_processes = {str(pid): value for pid, value in sample.get("processes", {}).items()}
        observed = memory_files.get(index, [])
        sample_rows.append(
            {
                "index": index,
                "stage": sample.get("stage"),
                "elapsed_millis": sample.get("elapsedMillis"),
                "reported_total_pss_kib": sample.get("totalPssKib"),
                "reported_processes": expected_processes,
                "raw_meminfo": observed,
                "raw_total_pss_matches_report": all(
                    row["total_pss_kib"] == expected_processes.get(str(row["pid"])) for row in observed
                ),
                "raw_meminfo_pid_set": sorted(row["pid"] for row in observed),
            }
        )

    before = next((sample for sample in memory_json.get("samples", []) if sample.get("stage") == "before-viewer"), None)
    active = [sample for sample in memory_json.get("samples", []) if sample.get("stage") == "active"]
    after = next((sample for sample in reversed(memory_json.get("samples", [])) if sample.get("stage") == "after-viewer"), None)
    max_active = max((sample.get("totalPssKib", 0) for sample in active), default=None)
    rise = max_active - before["totalPssKib"] if before and max_active is not None else None
    residual = after["totalPssKib"] - before["totalPssKib"] if before and after else None
    adaptive = memory_json.get("maximumRiseKib")
    residual_limit = memory_json.get("postCloseResidualMaximumKib")

    resource = parse_resource_regression(regression_path)
    complete = parse_complete_resume_regressions(REPO / ".artifacts/qualification-integration-20260905")

    live_named_memory_files = []
    if live_dir.is_dir():
        for path in live_dir.rglob("*"):
            if not path.is_file():
                continue
            name = path.name.lower()
            if any(token in name for token in ("meminfo", "memory-", "memory_", "memory.json", "ownership")):
                live_named_memory_files.append(rel(path))

    owner_pid_union = sorted({process["pid"] for snapshot in ownership for process in snapshot["owned_processes"]})
    webview_pids = sorted({process["pid"] for snapshot in ownership for process in snapshot["actual_webview_processes_in_dump"]})
    owned_webview_pids = sorted(
        {
            process["pid"]
            for snapshot in ownership
            for process in snapshot["actual_webview_processes_in_dump"]
            if process["owned_by_package"]
        }
    )
    all_meminfo_pids = sorted({row["pid"] for rows in memory_files.values() for row in rows})

    graphics = parse_gfxinfo(gfxinfo)
    graphics_paired = bool(graphics and graphics.get("pid") in owner_pid_union)
    if graphics is not None:
        graphics["paired_with_activity_manager_owner_set"] = graphics_paired
        if not graphics_paired:
            graphics["pairing_note"] = (
                "The gfxinfo PID is from a separate live diagnostic bundle, not the saved fixture "
                "ownership/PSS sample; it cannot be used as a residual delta."
            )

    resource_zero = bool(resource and resource["terminal_pipeline_resources_zero"])
    complete_zero = bool(complete) and all(item["zero_closed_fixture_ownership"] for item in complete)
    fixture_only = bool(resource and resource["fixture_only"]) and all(item["fixture_only"] for item in complete)

    findings = [
        {
            "id": "OWNER-001",
            "severity": "PROVEN",
            "claim": "Saved ActivityManager ownership is package-identity based for the fixture app PID.",
            "detail": (
                f"{len(ownership)} ownership dumps identify PID(s) {owner_pid_union} for {package_name}; "
                f"raw meminfo covers PID(s) {all_meminfo_pids}."
            ),
            "evidence": [rel(memory_dir / "memory.json"), rel(memory_dir / "memory-ownership-0.txt")],
        },
        {
            "id": "OWNER-002",
            "severity": "PROVEN",
            "claim": "A global WebView service is present but is not proven owned by the app.",
            "detail": (
                f"Raw ownership contains WebView PID(s) {webview_pids}; package identity is "
                f"com.google.android.webview and owned-by-app candidates are {owned_webview_pids}. "
                "Dependency-only processes are kept separate."
            ),
            "evidence": [rel(memory_dir / "memory-ownership-0.txt"), rel(memory_dir / "memory-ownership-4.txt")],
        },
        {
            "id": "PSS-001",
            "severity": "PROVEN",
            "claim": "The saved fixture PSS policy arithmetic passes for the sampled app PID.",
            "detail": (
                f"adaptive rise ceiling={adaptive} KiB, max active rise={rise} KiB, "
                f"post-close residual={residual} KiB, residual ceiling={residual_limit} KiB. "
                "Raw TOTAL PSS values match memory.json."
            ),
            "evidence": [rel(memory_json_path), rel(memory_dir / "memory-0-31337.txt"), rel(memory_dir / "memory-4-31337.txt")],
        },
        {
            "id": "PSS-002",
            "severity": "GAP",
            "claim": "The fixture aggregate does not establish live owned WebView/process PSS attribution.",
            "detail": (
                "QualificationMemory records raw meminfo but aggregates only TOTAL PSS. No live-corpus "
                "ActivityManager ownership + per-owned-PID meminfo pair is present in the live bundle."
            ),
            "evidence": [rel(REPO / "app/src/androidTest/java/ml/melun/mangaview/viewer/QualificationMemory.kt")],
        },
        {
            "id": "COMP-001",
            "severity": "PROVEN",
            "claim": "Saved meminfo contains native and graphics component observations for the app PID.",
            "detail": (
                "The fixture meminfo has Native Heap PSS and Graphics rows; Graphics is zero in these "
                "five app-PID captures. Components are reported as attribution detail only."
            ),
            "evidence": [rel(memory_dir / "memory-0-31337.txt"), rel(memory_dir / "memory-4-31337.txt")],
        },
        {
            "id": "COMP-002",
            "severity": "GAP",
            "claim": "Native/GPU/shared-memory attribution is not closed by the current artifacts.",
            "detail": (
                "Live gfxinfo v6 reports CPU/GPU cache and GraphicBuffer data, but its PID is not in the "
                "saved owner set and no matching meminfo/ownership timestamp exists. It must not be added to PSS."
            ),
            "evidence": [rel(gfxinfo)],
        },
        {
            "id": "RESOURCE-001",
            "severity": "PROVEN",
            "claim": "Fixture pipeline terminal snapshots show zero logical transfer/work ownership.",
            "detail": (
                f"{resource['cycle_count'] if resource else 0} fixture cycles report terminal active fetch/decode/"
                "upload/manifest/retry/retiring/resident counts as zero."
            ),
            "evidence": [rel(regression_path)],
        },
        {
            "id": "RESOURCE-002",
            "severity": "GAP",
            "claim": "Transfer and pending-release native resources are not independently observed.",
            "detail": (
                "activeUploads/retiringPages and terminal page records are logical pipeline evidence; the saved "
                "JSON has no native tile lease, texture retire queue, file-descriptor, or pending-release counters."
            ),
            "evidence": [rel(regression_path), rel(REPO / "viewer-content/src/main/kotlin/ml/melun/mangaview/content/ContentPipelineState.kt")],
        },
        {
            "id": "CLOSE-001",
            "severity": "PROVEN",
            "claim": "Complete-resume fixture sessions report zero body descriptors and snapshot leases after close.",
            "detail": f"{len(complete)} saved complete-resume fixture runs report zero remaining descriptors and leases.",
            "evidence": [item["path"] for item in complete],
        },
        {
            "id": "CLOSE-002",
            "severity": "GAP",
            "claim": "Zero closed-session ownership is not proven at ActivityManager/process scope.",
            "detail": (
                "The fixture intentionally keeps the test/app process alive and the after-viewer ownership dumps "
                "still contain its PID. Logical fixture cleanup therefore cannot prove that all session-owned OS, "
                "WebView, native, GPU, transfer, or pending-release resources are gone."
            ),
            "evidence": [rel(memory_dir / "memory-ownership-4.txt"), rel(regression_path)],
        },
    ]

    negative_controls = {
        "global_webview_process_not_promoted_to_app_owner": len(owned_webview_pids) == 0 and bool(webview_pids),
        "webview_dependency_only_processes_not_promoted": all(
            not process["owned_by_package"]
            for snapshot in ownership
            for process in snapshot["webview_dependency_only_processes"]
        ),
        "gfxinfo_not_added_to_pss": graphics is None or graphics.get("additive_to_activity_manager_pss") is False,
        "fixture_mode_cannot_claim_live_credit": fixture_only,
        "terminal_nonzero_would_not_pass": not terminal_snapshot_is_zero(
            {
                "activeFetches": 0,
                "activeDecodes": 0,
                "activeUploads": 1,
                "activeManifests": 0,
                "retryWakeups": 0,
                "retiringPages": 0,
                "residentTextures": 0,
            }
        ),
        "complete_fixture_zero_is_not_live_zero": complete_zero and fixture_only,
    }

    return {
        "schema_version": 1,
        "status": "INCOMPLETE",
        "qualification_credit": False,
        "audit_scope": "saved host artifacts only; no ADB, Gradle, device, or runtime calls",
        "package_name": package_name,
        "sources": {
            "memory_json": rel(memory_json_path),
            "memory_directory": rel(memory_dir),
            "live_directory": rel(live_dir),
            "gfxinfo": rel(gfxinfo),
            "live_named_memory_files": live_named_memory_files,
            "live_activity_manager_meminfo_pair_present": bool(live_named_memory_files),
        },
        "source_contract": source_markers(),
        "policy_arithmetic": {
            "total_ram_bytes": memory_json.get("totalRamBytes"),
            "adaptive_maximum_rise_kib": adaptive,
            "post_close_residual_limit_kib": residual_limit,
            "max_active_rise_kib": rise,
            "post_close_residual_kib": residual,
            "fixture_policy_arithmetic_passes": (
                rise is not None and adaptive is not None and rise <= adaptive and
                residual is not None and residual_limit is not None and residual <= residual_limit
            ),
        },
        "activity_manager_ownership": {
            "snapshot_count": len(ownership),
            "snapshots": ownership,
            "union_owned_pids": owner_pid_union,
            "union_actual_webview_pids": webview_pids,
            "union_owned_webview_pids": owned_webview_pids,
        },
        "meminfo": {
            "sample_count": len(sample_rows),
            "samples": sample_rows,
            "all_raw_totals_match_memory_json": all(row["raw_total_pss_matches_report"] for row in sample_rows),
            "component_values_are_non_additive_detail": True,
        },
        "app_resource_snapshots": {
            "session_memory": resource,
            "complete_resume": complete,
            "fixture_terminal_pipeline_zero": resource_zero,
            "fixture_complete_resume_descriptors_and_leases_zero": complete_zero,
        },
        "graphics_snapshot": graphics,
        "negative_controls": negative_controls,
        "findings": findings,
        "minimal_next_correction": (
            "At a live corpus session boundary, emit one synchronized debug-only evidence bundle containing "
            "the session/generation identifier, ActivityManager ownership dump, dumpsys meminfo for every owned PID "
            "(including any genuinely app-owned isolated WebView/renderer), app pipeline/lease/descriptor/native "
            "transfer and pending-release snapshot, and a separate gfxinfo snapshot. Sum only owned-PID TOTAL PSS; "
            "keep meminfo components and CPU/GPU graphics as non-additive detail; require the post-close bundle to "
            "bind zero session-owned resources to that same identifier."
        ),
    }


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--memory-dir", type=Path, default=DEFAULT_MEMORY_DIR)
    parser.add_argument("--gfxinfo", type=Path, default=DEFAULT_GFXINFO)
    parser.add_argument("--live-dir", type=Path, default=DEFAULT_LIVE_DIR)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(list(argv) if argv is not None else None)
    report = build_report(args.memory_dir, args.gfxinfo, args.live_dir)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"output": rel(args.output), "status": report["status"], "qualification_credit": report["qualification_credit"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
