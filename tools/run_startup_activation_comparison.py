#!/usr/bin/env python3
"""Run the frozen, diagnostic-only EAGER/FIRST_USE startup comparison.

This is a host orchestrator, not a performance analyzer.  It consumes the
STARTUP contract and policy, invokes exactly one Android instrumentation trial
at a time, force-stopping only the target app between trials, and pulls the
device-produced raw artifacts.  The execution path is deliberately opt-in:
without ``--execute`` this module only validates the contract and prints a
plan.  It never builds, installs, clears or primes cache, waits for readiness,
or turns a latch/proxy timestamp into physical-display evidence.
"""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
import hashlib
import json
import math
import os
from pathlib import Path
import secrets
import shutil
import statistics
import subprocess
import sys
import time
from typing import Any, Mapping, Sequence


DESIGNATED_SERIAL = "emulator-5554"
DESIGNATED_AVD = "MangaViewerApi35"
PACKAGE_NAME = "ml.melun.mangaview"
TEST_PACKAGE = "ml.melun.mangaview.test"
TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
SOURCE_ID = "ntk"
SERIES_KEY = "/webtoon/57451201"
EPISODE_KEY = "/webtoon/57451201/jjaptoon-1341148"
EXPECTED_PAGE_COUNT = 132
REMOTE_ARTIFACT_ROOT = "/sdcard/Android/data/ml.melun.mangaview/files/ux-evidence/startup-activation"
MODES = ("EAGER", "FIRST_USE")
TERMINAL_TIMESTAMP_KINDS = {"CANCELLED", "DROPPED", "CONTEXT_LOST"}
T_CRITICAL_95_DF4 = 2.7764451051977987
T_CRITICAL_95_DF9 = 2.2621571628540993


class DriverError(ValueError):
    """A fail-closed contract, preflight, or artifact error."""


class ContractError(DriverError):
    pass


class TrialError(DriverError):
    pass


@dataclass(frozen=True)
class ValidatedContract:
    contract_path: Path
    policy_path: Path
    contract: Mapping[str, Any]
    policy: Mapping[str, Any]
    test_selector: str
    test_class: str
    test_method: str
    series_key: str
    episode_key: str
    artifact_prefix: str
    remote_artifact_root: str
    primary_metric: str
    pair_order: tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: bytes = b""
    stderr: bytes = b""


class CommandTimeout(DriverError):
    """A command timed out after the runner attempted to terminate its own process."""

    def __init__(self, argv: Sequence[str], result: CommandResult, *, termination_verified: bool) -> None:
        self.argv = tuple(str(value) for value in argv)
        self.result = result
        self.termination_verified = termination_verified
        state = "verified terminated" if termination_verified else "termination not verified"
        super().__init__(f"command timed out ({state}): {' '.join(self.argv)}")


def _as_mapping(value: Any, path: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ContractError(f"{path} must be an object")
    return value


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def _text(value: Any) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False).lower()


def _read_json(path: Path) -> Mapping[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ContractError(f"cannot read JSON {path}: {exc}") from exc
    return _as_mapping(value, str(path))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        raise DriverError(f"cannot hash {path}: {exc}") from exc
    return digest.hexdigest()


def _safe_prefix(value: Any, path: str) -> str:
    if not isinstance(value, str) or not value or not all(
        character.isalnum() or character in "_-" for character in value
    ):
        raise ContractError(f"{path} must contain only letters, numbers, '_' or '-'")
    return value


def _validate_pair_policy(contract: Mapping[str, Any], policy: Mapping[str, Any]) -> tuple[tuple[str, str], ...]:
    policy_pairs = _as_mapping(policy.get("pairs"), "policy.pairs")
    contract_policy = _as_mapping(contract.get("policy"), "contract.policy")
    _require(policy_pairs.get("initial") == 5, "frozen policy initial pair count must be 5")
    _require(policy_pairs.get("maximum") == 10, "frozen policy maximum pair count must be 10")
    _require(contract_policy.get("initialPairs") == 5, "contract initial pair count must be 5")
    _require(contract_policy.get("maximumPairs") == 10, "contract maximum pair count must be 10")
    _require(
        policy_pairs.get("extensionRule") ==
        "Collect pairs 1..5 first. Collect pairs 6..10 only if the two-sided 95% Student t CI of the five paired differences includes zero.",
        "extension rule is not the frozen five-pair CI rule",
    )
    order = policy_pairs.get("order")
    _require(isinstance(order, list) and len(order) == 10, "policy.pairs.order must contain ten pairs")
    normalized: list[tuple[str, str]] = []
    for index, pair in enumerate(order, start=1):
        _require(isinstance(pair, list) and len(pair) == 2, f"policy order pair {index} must contain two modes")
        modes = (pair[0], pair[1])
        expected = ("EAGER", "FIRST_USE") if index % 2 else ("FIRST_USE", "EAGER")
        _require(modes == expected, f"policy order pair {index} is {modes}, expected {expected}")
        normalized.append(modes)
    return tuple(normalized)


def validate_contract(
    contract: Mapping[str, Any],
    policy: Mapping[str, Any],
    *,
    contract_path: Path | None = None,
    policy_path: Path | None = None,
) -> ValidatedContract:
    """Validate the frozen method and return only values the driver may use."""

    _require(contract.get("schemaVersion") == 1, "unsupported STARTUP contract schema")
    _require(contract.get("owner") == "STARTUP", "contract owner must be STARTUP")
    _require(contract.get("methodFreeze") == "FROZEN_BEFORE_MEASUREMENTS", "STARTUP method is not frozen")
    _require(contract.get("status") == "READY_FOR_EXECUTION", "contract is not ready for execution")
    _require(contract.get("oneTrialPerInstrumentationInvocation") is True, "one-trial invocation rule is required")
    selector = contract.get("testSelector")
    _require(selector == "ml.melun.mangaview.viewer.StartupActivationBenchmarkTest#captureSingleStartupTrial",
             "unexpected startup instrumentation selector")
    _require(contract.get("negativeControlSelector") ==
             "ml.melun.mangaview.viewer.StartupActivationBenchmarkPolicyTest",
             "unexpected negative-control selector")
    test_class, test_method = selector.split("#", 1)

    arguments = _as_mapping(contract.get("arguments"), "contract.arguments")
    mode_argument = _as_mapping(arguments.get("startupMode"), "arguments.startupMode")
    _require(mode_argument.get("required") is True and mode_argument.get("values") == list(MODES),
             "startupMode must require exactly EAGER and FIRST_USE")
    pair_argument = _as_mapping(arguments.get("startupPair"), "arguments.startupPair")
    _require(pair_argument.get("required") is True and pair_argument.get("type") == "integer" and
             pair_argument.get("range") == "1..10", "startupPair range is not frozen")
    trial_argument = _as_mapping(arguments.get("startupTrial"), "arguments.startupTrial")
    _require(trial_argument.get("required") is True and trial_argument.get("values") == [0, 1],
             "startupTrial must be 0 or 1")
    series_argument = _as_mapping(arguments.get("startupSeriesKey"), "arguments.startupSeriesKey")
    episode_argument = _as_mapping(arguments.get("startupEpisodeKey"), "arguments.startupEpisodeKey")
    _require(series_argument.get("default") == SERIES_KEY, "contract changed the frozen series")
    _require(episode_argument.get("default") == EPISODE_KEY, "contract changed the frozen episode")
    prefix_argument = _as_mapping(arguments.get("startupArtifactPrefix"), "arguments.startupArtifactPrefix")
    prefix = _safe_prefix(prefix_argument.get("default"), "arguments.startupArtifactPrefix.default")

    method = _as_mapping(contract.get("frozenMethod"), "contract.frozenMethod")
    endpoint_text = _text(method.get("endpoint"))
    for marker in (
        "readableactualcontent",
        "fullvisualcoverage",
        "fullactualcoverage",
        "submittedatnanos>0",
        "bufferframeid>0",
        "cancelled",
        "dropped",
        "context_lost",
        "presentedimageregion",
        "verified image identity",
    ):
        _require(marker in endpoint_text, f"endpoint contract omits strict marker: {marker}")
    cache_text = _text(method.get("cache"))
    for marker in ("app_complete_resume_v1", "complete_lease_opened", "manifestpagecount=132", "unchanged"):
        _require(marker in cache_text, f"cache contract omits route proof marker: {marker}")
    _require("initialresponsestartedatnanos=null" in cache_text,
             "cache contract must retain the supplementary null response rule")
    timestamps = method.get("timestamps")
    _require(isinstance(timestamps, list), "frozenMethod.timestamps must be a list")
    for marker in ("entryRequestedAtNanos", "openStartedAtNanos", "manifestReadyAtNanos",
                   "firstActualSubmittedAtNanos", "firstProxyTimestampNanos"):
        _require(marker in timestamps, f"frozen timestamps omit {marker}")

    primary = _as_mapping(contract.get("primaryMetric"), "contract.primaryMetric")
    primary_name = primary.get("name")
    primary_formula = primary.get("formula")
    _require(isinstance(primary_name, str) and primary_name, "primary metric name is missing")
    _require(isinstance(primary_formula, str), "primary metric formula is missing")
    _require(primary_name == policy.get("primaryMetric"), "contract/policy primary metric mismatch")
    _require(primary_formula == policy.get("primaryFormula"), "contract/policy primary formula mismatch")
    _require("entryRequestedAtNanos" in primary_formula and "firstActualSubmittedAtNanos" in primary_formula,
             "primary formula must use the shared entry boundary and native submission")
    _require("openStartedAtNanos" not in primary_formula,
             "open-to-submission cannot be the primary metric")

    outputs = _as_mapping(contract.get("outputs"), "contract.outputs")
    _require(outputs.get("root", "").rstrip("/") == REMOTE_ARTIFACT_ROOT,
             "unexpected device artifact root")
    _require(outputs.get("perTrial") == ["trial.json", "presentation-evidence.tsv"],
             "per-trial raw artifact list changed")
    _require(outputs.get("failure") == "trial-failure.json", "failure artifact name changed")
    required_fields = outputs.get("trialJsonRequiredFields")
    _require(isinstance(required_fields, list), "trialJsonRequiredFields must be a list")
    for field in ("schemaVersion", "diagnosticOnly", "corpusCredit", "pair", "trialInPair", "mode",
                  "apkSha256", "episode", "savedPosition", "cacheBefore", "cacheAfter",
                  "cacheUnchanged", "cachedResume", "timestamps", "durationsMs",
                  "initialResponseStartedAtNanos", "observedAnchor", "candidate",
                  "physicalPresentationQualified"):
        _require(field in required_fields, f"trial output contract omits {field}")

    contract_policy = _as_mapping(contract.get("policy"), "contract.policy")
    credit_text = str(contract_policy.get("credit", "")).lower()
    _require("corpuscredit=0" in credit_text and "physicalpresentationqualified=false" in credit_text,
             "contract must remain diagnostic-only")
    measurement = _as_mapping(policy.get("measurement"), "policy.measurement")
    _require(measurement.get("physicalPresentationQualified") is False and measurement.get("corpusCredit") == 0,
             "frozen policy permits physical or corpus credit")
    _require(policy.get("schemaVersion") == 1 and policy.get("methodFreeze") == "FROZEN_BEFORE_MEASUREMENTS",
             "unsupported or unfrozen startup policy")
    _require(policy.get("mode") == "DIAGNOSTIC_NO_CORPUS_CREDIT", "startup policy mode changed")
    policy_preconditions = " ".join(str(item).lower() for item in policy.get("preconditions", []))
    for marker in ("no cache clearing", "cache injection", "complete ntk snapshot", "no provider prepare",
                   "complete_lease_opened", "unchanged"):
        _require(marker in policy_preconditions, f"policy preconditions omit {marker}")
    pair_order = _validate_pair_policy(contract, policy)

    return ValidatedContract(
        contract_path=(contract_path or Path("STARTUP-CONTRACT.json")).resolve(),
        policy_path=(policy_path or Path("startup-activation-policy.json")).resolve(),
        contract=contract,
        policy=policy,
        test_selector=selector,
        test_class=test_class,
        test_method=test_method,
        series_key=SERIES_KEY,
        episode_key=EPISODE_KEY,
        artifact_prefix=prefix,
        remote_artifact_root=outputs["root"].rstrip("/"),
        primary_metric=primary_name,
        pair_order=pair_order,
    )


def parse_position(page_key: str | None, offset: str | None) -> dict[str, Any] | None:
    if page_key is None and offset is None:
        return None
    if page_key is None or offset is None:
        raise DriverError("saved page and saved offset must be supplied together")
    if not page_key:
        raise DriverError("saved page key must not be empty")
    try:
        parsed_offset = int(offset)
    except ValueError as exc:
        raise DriverError("saved offset must be an integer") from exc
    return {"pageKey": page_key, "offsetInPageUnits": parsed_offset}


def build_plan(
    validated: ValidatedContract,
    *,
    pairs: int = 5,
    seed: int | None = None,
    expected_position: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    if pairs not in (5, 10):
        raise DriverError("pairs must be 5 or 10")
    if seed is None:
        seed = secrets.randbits(63)
    if not isinstance(seed, int) or seed < 0:
        raise DriverError("seed must be a non-negative integer")
    schedule = [
        {
            "pair": pair,
            "trialInPair": trial,
            "mode": validated.pair_order[pair - 1][trial],
        }
        for pair in range(1, pairs + 1)
        for trial in (0, 1)
    ]
    return {
        "schemaVersion": 1,
        "diagnosticOnly": True,
        "corpusCredit": 0,
        "physicalPresentationQualified": False,
        "contract": str(validated.contract_path),
        "policy": str(validated.policy_path),
        "contractStatus": validated.contract["status"],
        "methodFreeze": validated.contract["methodFreeze"],
        "randomSeed": seed,
        "requestedPairs": pairs,
        "pairScheduleSource": "frozen policy order",
        "episode": {"sourceId": SOURCE_ID, "seriesKey": validated.series_key, "episodeKey": validated.episode_key},
        "expectedSavedPosition": expected_position,
        "primaryMetric": {
            "name": validated.primary_metric,
            "formula": validated.contract["primaryMetric"]["formula"],
            "pairedDifference": validated.contract["primaryMetric"]["pairedDifference"],
        },
        "noCacheManipulation": True,
        "noReadinessWait": True,
        "schedule": schedule,
    }


class SubprocessRunner:
    def run(self, argv: Sequence[str], *, timeout: float | None = None) -> CommandResult:
        process: subprocess.Popen[bytes] | None = None
        try:
            process = subprocess.Popen(list(argv), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            stdout, stderr = process.communicate(timeout=timeout)
        except OSError as exc:
            raise DriverError(f"cannot execute {' '.join(map(str, argv))}: {exc}") from exc
        except subprocess.TimeoutExpired as exc:
            partial_stdout = _bytes(getattr(exc, "stdout", b""))
            partial_stderr = _bytes(getattr(exc, "stderr", b""))
            terminated = False
            try:
                if process is not None and process.poll() is None:
                    process.terminate()
                if process is not None:
                    stdout, stderr = process.communicate(timeout=5)
                terminated = process is not None and process.poll() is not None
            except subprocess.TimeoutExpired:
                try:
                    if process is not None and process.poll() is None:
                        process.kill()
                    if process is not None:
                        stdout, stderr = process.communicate(timeout=5)
                except (OSError, subprocess.TimeoutExpired):
                    stdout, stderr = partial_stdout, partial_stderr
                terminated = process is not None and process.poll() is not None
            except OSError:
                stdout, stderr = partial_stdout, partial_stderr
                terminated = process is not None and process.poll() is not None
            stdout = stdout or partial_stdout
            stderr = stderr or partial_stderr
            returncode = process.returncode if process is not None and process.returncode is not None else -124
            raise CommandTimeout(
                argv,
                CommandResult(returncode, stdout or b"", stderr or b""),
                termination_verified=terminated,
            ) from exc
        except Exception:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            raise
        return CommandResult(process.returncode if process is not None else 0, stdout or b"", stderr or b"")


def _bytes(value: Any) -> bytes:
    if isinstance(value, bytes):
        return value
    if isinstance(value, str):
        return value.encode()
    return b"" if value is None else str(value).encode()


def _result_text(result: CommandResult) -> str:
    return (_bytes(result.stdout) + _bytes(result.stderr)).decode(errors="replace")


def validate_instrumentation_result(result: CommandResult) -> int:
    """Require the Android runner's test summary, not merely adb's exit code."""
    import re

    text = _result_text(result)
    if result.returncode != 0:
        raise TrialError(f"instrumentation exited {result.returncode}")
    if re.search(r"(?im)^\s*(?:FAILURES!!!|INSTRUMENTATION_FAILED)\b", text):
        raise TrialError("instrumentation reported a framework/test failure")
    if re.search(r"(?im)^\s*INSTRUMENTATION_STATUS_CODE\s*:\s*-[12]\s*$", text):
        raise TrialError("instrumentation reported failure status code -1/-2")
    matches = list(re.finditer(r"(?im)^\s*OK\s*\(\s*([1-9]\d*)\s+tests?\s*\)\s*$", text))
    if not matches:
        raise TrialError("instrumentation did not finish with OK (N tests)")
    return int(matches[-1].group(1))


def host_process_snapshot() -> list[dict[str, Any]]:
    """Return a process snapshot without killing or mutating anything."""
    if os.name == "nt":
        executable = shutil.which("powershell.exe") or shutil.which("pwsh.exe")
        if not executable:
            raise DriverError("PowerShell is required to verify host processes")
        query = (
            "Get-CimInstance Win32_Process | "
            "Select-Object ProcessId,ParentProcessId,Name,CommandLine | "
            "ConvertTo-Json -Compress"
        )
        result = SubprocessRunner().run([executable, "-NoProfile", "-NonInteractive", "-Command", query], timeout=30)
        if result.returncode != 0:
            raise DriverError(f"host process snapshot failed: {_result_text(result)}")
        try:
            decoded = json.loads(_result_text(result)) if _result_text(result).strip() else []
        except json.JSONDecodeError as exc:
            raise DriverError(f"host process snapshot was not JSON: {exc}") from exc
        rows = decoded if isinstance(decoded, list) else [decoded]
        return [
            {
                "pid": int(row.get("ProcessId", 0)),
                "parentPid": int(row.get("ParentProcessId", 0)),
                "name": row.get("Name") or "",
                "commandLine": row.get("CommandLine") or "",
            }
            for row in rows if isinstance(row, Mapping)
        ]
    result = SubprocessRunner().run(["ps", "-eo", "pid=,ppid=,comm=,args="], timeout=30)
    if result.returncode != 0:
        raise DriverError(f"host process snapshot failed: {_result_text(result)}")
    rows = []
    for line in _result_text(result).splitlines():
        parts = line.strip().split(None, 3)
        if len(parts) >= 3:
            rows.append({"pid": int(parts[0]), "parentPid": int(parts[1]), "name": parts[2],
                         "commandLine": parts[3] if len(parts) == 4 else parts[2]})
    return rows


def _ancestor_pids(rows: Sequence[Mapping[str, Any]], pid: int) -> set[int]:
    parents = {int(row.get("pid", 0)): int(row.get("parentPid", 0)) for row in rows}
    result: set[int] = set()
    current = pid
    while current in parents and current not in result:
        result.add(current)
        current = parents[current]
    return result


def blocking_host_processes(rows: Sequence[Mapping[str, Any]], *, own_pid: int | None = None) -> list[dict[str, Any]]:
    own_pid = os.getpid() if own_pid is None else own_pid
    ignored = _ancestor_pids(rows, own_pid)
    blocked: list[dict[str, Any]] = []
    for row in rows:
        pid = int(row.get("pid", 0))
        if pid in ignored:
            continue
        name = str(row.get("name", ""))
        command = str(row.get("commandLine", ""))
        text = f"{name} {command}".lower()
        if re_search_any(text, (
            r"run_startup_activation_comparison",
            r"run_viewer_diagnostic",
            r"qualify_200",
            r"simpleperf",
            r"perfetto",
            r"\bam\s+instrument\b",
        )):
            blocked.append(dict(row))
            continue
        if re_search_any(text, (r"gradlew(?:\.bat)?\b", r"\bgradle\s+")) and "gradledaemon" not in text:
            blocked.append(dict(row))
    return blocked


def re_search_any(value: str, patterns: Sequence[str]) -> bool:
    import re
    return any(re.search(pattern, value) for pattern in patterns)


class AdbClient:
    def __init__(self, executable: str, serial: str, runner: Any) -> None:
        self.executable = executable
        self.serial = serial
        self.runner = runner

    def command(self, *arguments: str) -> list[str]:
        return [self.executable, "-s", self.serial, *arguments]

    def run(self, *arguments: str, check: bool = True, timeout: float = 60) -> CommandResult:
        result = self.runner.run(self.command(*arguments), timeout=timeout)
        if check and result.returncode != 0:
            raise DriverError(f"adb {' '.join(arguments)} failed: {_result_text(result)}")
        return result

    def text(self, *arguments: str, check: bool = True, timeout: float = 60) -> str:
        return _result_text(self.run(*arguments, check=check, timeout=timeout)).strip()


def _device_blockers(ps_text: str) -> list[str]:
    lines = [line.strip() for line in ps_text.splitlines() if line.strip()]
    return [line for line in lines if re_search_any(line.lower(), (
        r"\bam\s+instrument\b", r"androidx\.test\.runner", r"simpleperf", r"perfetto",
        r"run_viewer", r"qualify_200", r"startup-activation",
    ))]


def _first_non_ok_line(text: str) -> str:
    for line in text.splitlines():
        value = line.strip()
        if value and value.upper() != "OK":
            return value
    return ""


def _base_apk_path(pm_output: str) -> str:
    for line in pm_output.splitlines():
        value = line.strip()
        if value.startswith("package:") and value.endswith("/base.apk"):
            return value.removeprefix("package:")
    raise DriverError("installed base APK path was not reported")


def _inventory(root: Path) -> list[dict[str, Any]]:
    files: list[dict[str, Any]] = []
    for path in sorted(root.rglob("*")):
        if path.is_file():
            files.append({
                "path": path.relative_to(root).as_posix(),
                "size": path.stat().st_size,
                "sha256": sha256_file(path),
            })
    return files


def _write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")


def _int(value: Any, path: str, *, positive: bool = False) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise TrialError(f"{path} must be an integer")
    if positive and value <= 0:
        raise TrialError(f"{path} must be positive")
    return value


def _same_episode(value: Any, *, path: str) -> Mapping[str, Any]:
    episode = _as_mapping(value, path)
    if episode.get("sourceId") != SOURCE_ID or episode.get("seriesKey") != SERIES_KEY or episode.get("episodeKey") != EPISODE_KEY:
        raise TrialError(f"{path} is not the exact frozen NTK episode")
    return episode


def _same_position(value: Any, expected: Mapping[str, Any] | None, *, path: str) -> Mapping[str, Any]:
    position = _as_mapping(value, path)
    if not isinstance(position.get("pageKey"), str) or not position["pageKey"]:
        raise TrialError(f"{path}.pageKey is missing")
    _int(position.get("offsetInPageUnits"), f"{path}.offsetInPageUnits")
    if expected is not None and dict(position) != dict(expected):
        raise TrialError(f"{path} differs from the requested exact saved position")
    return position


def _cache_fingerprint(value: Any, *, path: str) -> Mapping[str, Any]:
    cache = _as_mapping(value, path)
    if cache.get("exists") is not True:
        raise TrialError(f"{path} does not prove an existing complete snapshot")
    if not isinstance(cache.get("path"), str) or "app_complete_resume_v1" not in cache["path"]:
        raise TrialError(f"{path} is not the complete-resume snapshot")
    _int(cache.get("byteCount"), f"{path}.byteCount", positive=True)
    digest = cache.get("sha256")
    if not isinstance(digest, str) or not re_fullmatch(r"[0-9a-fA-F]{64}", digest):
        raise TrialError(f"{path}.sha256 is missing or malformed")
    return cache


def re_fullmatch(pattern: str, value: str) -> bool:
    import re
    return re.fullmatch(pattern, value) is not None


def _bool(value: Any, path: str) -> bool:
    if isinstance(value, bool):
        return value
    raise TrialError(f"{path} must be boolean")


def _parse_tsv_bool(value: str, path: str) -> bool:
    if value.lower() == "true":
        return True
    if value.lower() == "false":
        return False
    raise TrialError(f"{path} is not a boolean")


def _validate_presentation_evidence(
    path: Path,
    candidate: Mapping[str, Any],
    timestamps: Mapping[str, Any],
) -> None:
    required_columns = {
        "rendererIdentity", "token", "generation", "submittedAtNanos", "readableActualContent",
        "fullVisualCoverage", "fullActualCoverage", "timestampKind", "bufferFrameId",
        "anchorOffsetUnits", "userInputRevision",
    }
    try:
        with path.open("r", encoding="utf-8", newline="") as stream:
            reader = csv.DictReader(stream, delimiter="\t")
            if not reader.fieldnames or not required_columns.issubset(reader.fieldnames):
                raise TrialError("presentation-evidence.tsv omits strict endpoint columns")
            rows = list(reader)
    except OSError as exc:
        raise TrialError(f"cannot read {path}: {exc}") from exc
    matches = []
    for index, row in enumerate(rows):
        try:
            row_values = {
                "rendererIdentity": int(row["rendererIdentity"]),
                "token": int(row["token"]),
                "generation": int(row["generation"]),
                "submittedAtNanos": int(row["submittedAtNanos"]),
                "bufferFrameId": int(row["bufferFrameId"]),
                "anchorOffsetUnits": int(row["anchorOffsetUnits"]),
                "userInputRevision": int(row["userInputRevision"]),
            }
        except (KeyError, TypeError, ValueError) as exc:
            raise TrialError(f"presentation row {index} has malformed identity fields") from exc
        flags = {
            name: _parse_tsv_bool(row[name], f"presentation row {index}.{name}")
            for name in ("readableActualContent", "fullVisualCoverage", "fullActualCoverage")
        }
        kind = row["timestampKind"].strip().upper()
        is_candidate = (
            row_values["rendererIdentity"] == candidate["rendererIdentity"] and
            row_values["token"] == candidate["token"] and
            row_values["generation"] == candidate["generation"] and
            row_values["submittedAtNanos"] == timestamps["firstActualSubmittedAtNanos"] and
            row_values["bufferFrameId"] == candidate["bufferFrameId"] and
            row_values["anchorOffsetUnits"] == candidate["anchorOffsetUnits"] and
            row_values["userInputRevision"] == 0 and
            flags["readableActualContent"] and flags["fullVisualCoverage"] and flags["fullActualCoverage"] and
            row_values["submittedAtNanos"] > 0 and row_values["bufferFrameId"] > 0 and
            kind not in TERMINAL_TIMESTAMP_KINDS
        )
        if is_candidate:
            matches.append(index)
    if len(matches) != 1:
        raise TrialError(f"expected exactly one strict native candidate row, found {len(matches)}")


def validate_trial(
    trial_dir: Path,
    trial: Mapping[str, Any],
    validated: ValidatedContract,
    *,
    pair: int,
    trial_in_pair: int,
    mode: str,
    apk_sha256: str,
    expected_position: Mapping[str, Any] | None,
    expected_cache_sha256: str | None = None,
) -> dict[str, Any]:
    if trial.get("valid") is not True:
        raise TrialError("trial is not marked valid")
    if trial.get("schemaVersion") != 1 or trial.get("diagnosticOnly") is not True:
        raise TrialError("trial is not a schema-1 diagnostic result")
    if trial.get("corpusCredit") != 0 or trial.get("physicalPresentationQualified") is not False:
        raise TrialError("trial attempted physical-display or corpus credit")
    if trial.get("pair") != pair or trial.get("trialInPair") != trial_in_pair or trial.get("mode") != mode:
        raise TrialError("trial pair, position, or mode does not match the frozen schedule")
    if trial.get("apkSha256", "").lower() != apk_sha256.lower():
        raise TrialError("trial APK hash differs from the preflight candidate")
    _same_episode(trial.get("episode"), path="episode")
    position = _same_position(trial.get("savedPosition"), expected_position, path="savedPosition")

    before = _cache_fingerprint(trial.get("cacheBefore"), path="cacheBefore")
    after = _cache_fingerprint(trial.get("cacheAfter"), path="cacheAfter")
    if trial.get("cacheUnchanged") is not True or dict(before) != dict(after):
        raise TrialError("complete-resume snapshot fingerprint changed")
    if expected_cache_sha256 is not None and before["sha256"].lower() != expected_cache_sha256.lower():
        raise TrialError("complete-resume snapshot fingerprint differs from the frozen first trial")
    if trial.get("initialResponseStartedAtNanos", "missing") is not None:
        raise TrialError("trial started a source response instead of using the complete cache route")
    cached = _as_mapping(trial.get("cachedResume"), "cachedResume")
    if cached.get("route") != "COMPLETE_LEASE_OPENED":
        raise TrialError(f"cachedResume route is {cached.get('route')!r}, not COMPLETE_LEASE_OPENED")
    _same_episode(cached.get("episode"), path="cachedResume.episode")
    if cached.get("manifestPageCount") != EXPECTED_PAGE_COUNT:
        raise TrialError("cachedResume manifest page count is not 132")
    _int(cached.get("routeAtNanos"), "cachedResume.routeAtNanos", positive=True)

    timestamps = _as_mapping(trial.get("timestamps"), "timestamps")
    for field in ("entryRequestedAtNanos", "openStartedAtNanos", "manifestReadyAtNanos", "firstActualSubmittedAtNanos"):
        _int(timestamps.get(field), f"timestamps.{field}", positive=True)
    proxy = timestamps.get("firstProxyTimestampNanos")
    if proxy is not None:
        _int(proxy, "timestamps.firstProxyTimestampNanos", positive=True)
    if timestamps["openStartedAtNanos"] < timestamps["entryRequestedAtNanos"]:
        raise TrialError("open timestamp precedes the shared entry boundary")
    if timestamps["firstActualSubmittedAtNanos"] < timestamps["entryRequestedAtNanos"]:
        raise TrialError("native submission precedes the shared entry boundary")

    durations = _as_mapping(trial.get("durationsMs"), "durationsMs")
    primary_value = durations.get(validated.primary_metric)
    if isinstance(primary_value, bool) or not isinstance(primary_value, (int, float)) or not math.isfinite(primary_value):
        raise TrialError(f"durationsMs.{validated.primary_metric} is unavailable")
    expected_primary = (timestamps["firstActualSubmittedAtNanos"] - timestamps["entryRequestedAtNanos"]) / 1_000_000.0
    if primary_value < 0 or not math.isclose(primary_value, expected_primary, rel_tol=1e-9, abs_tol=1e-6):
        raise TrialError("primary duration does not match the frozen entry-to-submission formula")

    if trial.get("observedUserInputRevision") != 0:
        raise TrialError("startup trial observed input before the endpoint")
    observed_anchor = _same_position(trial.get("observedAnchor"), position, path="observedAnchor")
    candidate = _as_mapping(trial.get("candidate"), "candidate")
    for field in ("token", "rendererIdentity", "generation", "bufferFrameId"):
        _int(candidate.get(field), f"candidate.{field}", positive=True)
    if candidate.get("anchorOffsetUnits") != position["offsetInPageUnits"]:
        raise TrialError("candidate anchor offset differs from the exact saved position")
    if candidate.get("regionPageKey") != position["pageKey"]:
        raise TrialError("candidate region is for the wrong page")
    for field in ("regionImageIdentityVerified", "readableActualContent", "fullVisualCoverage", "fullActualCoverage"):
        if candidate.get(field) is not True:
            raise TrialError(f"candidate.{field} is not true")
    for field in ("cancelled", "canceled", "dropped", "contextLost", "zeroBuffer", "wrongPage"):
        if field in candidate and candidate[field] is not False:
            raise TrialError(f"candidate.{field} invalidates the endpoint")
    if "pageMatches" in candidate and candidate["pageMatches"] is not True:
        raise TrialError("candidate.pageMatches is false")
    if trial.get("regionHistoryDropped") is True:
        raise TrialError("presentation region history was dropped")

    evidence_path = trial_dir / "presentation-evidence.tsv"
    _validate_presentation_evidence(evidence_path, candidate, timestamps)
    return {
        "pair": pair,
        "trialInPair": trial_in_pair,
        "mode": mode,
        "primaryMetricValueMs": float(primary_value),
        "episode": dict(trial["episode"]),
        "savedPosition": dict(position),
        "cacheSha256": before["sha256"].lower(),
        "candidate": {
            "rendererIdentity": candidate["rendererIdentity"],
            "token": candidate["token"],
            "generation": candidate["generation"],
            "bufferFrameId": candidate["bufferFrameId"],
            "regionPageKey": candidate["regionPageKey"],
        },
        "physicalPresentationQualified": False,
        "corpusCredit": 0,
    }


def extension_decision(records: Sequence[Mapping[str, Any]], validated: ValidatedContract) -> dict[str, Any]:
    by_pair: dict[int, dict[str, float]] = {}
    for record in records:
        pair = int(record["pair"])
        by_pair.setdefault(pair, {})[str(record["mode"])] = float(record["primaryMetricValueMs"])
    if any(pair not in by_pair or set(by_pair[pair]) != set(MODES) for pair in range(1, 6)):
        raise DriverError("cannot apply the extension rule before all five pairs are valid")
    differences = [by_pair[pair]["FIRST_USE"] - by_pair[pair]["EAGER"] for pair in range(1, 6)]
    mean = statistics.fmean(differences)
    standard_error = statistics.stdev(differences) / math.sqrt(len(differences)) if len(set(differences)) > 1 else 0.0
    margin = T_CRITICAL_95_DF4 * standard_error
    lower, upper = mean - margin, mean + margin
    return {
        "metric": validated.primary_metric,
        "pairedDifference": "FIRST_USE minus EAGER; positive values favor EAGER",
        "fivePairDifferencesMs": differences,
        "meanMs": mean,
        "ci95Ms": {"lower": lower, "upper": upper},
        "includesZero": lower <= 0.0 <= upper,
        "rule": validated.policy["pairs"]["extensionRule"],
        "physicalPresentationQualified": False,
        "corpusCredit": 0,
    }


def final_ten_pair_decision(records: Sequence[Mapping[str, Any]], validated: ValidatedContract) -> dict[str, Any]:
    """Summarize a completed ten-pair run with the df9 conclusion interval."""
    by_pair: dict[int, dict[str, float]] = {}
    for record in records:
        pair = int(record["pair"])
        by_pair.setdefault(pair, {})[str(record["mode"])] = float(record["primaryMetricValueMs"])
    if len(records) != 20 or any(
        pair not in by_pair or set(by_pair[pair]) != set(MODES) for pair in range(1, 11)
    ):
        raise DriverError("cannot conclude the ten-pair result before all twenty valid trials exist")
    differences = [by_pair[pair]["FIRST_USE"] - by_pair[pair]["EAGER"] for pair in range(1, 11)]
    mean = statistics.fmean(differences)
    standard_error = statistics.stdev(differences) / math.sqrt(len(differences)) if len(set(differences)) > 1 else 0.0
    margin = T_CRITICAL_95_DF9 * standard_error
    lower, upper = mean - margin, mean + margin
    includes_zero = lower <= 0.0 <= upper
    conclusion = "INCONCLUSIVE_CI_INCLUDES_ZERO" if includes_zero else (
        "EAGER_FASTER" if mean > 0.0 else "FIRST_USE_FASTER"
    )
    return {
        "metric": validated.primary_metric,
        "pairedDifference": "FIRST_USE minus EAGER; positive values favor EAGER",
        "tenPairDifferencesMs": differences,
        "meanMs": mean,
        "degreesOfFreedom": 9,
        "criticalT95": T_CRITICAL_95_DF9,
        "ci95Ms": {"lower": lower, "upper": upper},
        "includesZero": includes_zero,
        "conclusion": conclusion,
        "physicalPresentationQualified": False,
        "corpusCredit": 0,
    }


class StartupComparisonDriver:
    def __init__(
        self,
        validated: ValidatedContract,
        *,
        apk: Path,
        output: Path,
        adb_executable: str,
        pairs: int,
        seed: int,
        expected_position: Mapping[str, Any] | None,
        timeout_seconds: float,
        runner: Any | None = None,
    ) -> None:
        self.validated = validated
        self.apk = apk.resolve()
        self.output = output.resolve()
        self.pairs = pairs
        self.seed = seed
        self.expected_position = dict(expected_position) if expected_position is not None else None
        self.expected_cache_sha256: str | None = None
        self.timeout_seconds = timeout_seconds
        self.runner = runner or SubprocessRunner()
        self.adb = AdbClient(adb_executable, DESIGNATED_SERIAL, self.runner)

    def preflight(self, apk_sha256: str) -> dict[str, Any]:
        state = self.adb.text("get-state")
        if state != "device":
            raise DriverError(f"designated device state is {state!r}")
        avd = _first_non_ok_line(self.adb.text("emu", "avd", "name"))
        if avd != DESIGNATED_AVD:
            raise DriverError(f"unexpected AVD {avd!r}")
        pm_output = self.adb.text("shell", "pm", "path", PACKAGE_NAME)
        remote_apk = _base_apk_path(pm_output)
        remote_hash = self.adb.text("shell", "sha256sum", remote_apk).split()[0].lower()
        if remote_hash != apk_sha256.lower():
            raise DriverError("installed APK hash differs from local candidate")
        device_ps = self.adb.text("shell", "ps", "-A", "-o", "PID,PPID,NAME,ARGS", check=False)
        device_blockers = _device_blockers(device_ps)
        if device_blockers:
            raise DriverError(f"another device measurement is active: {device_blockers}")
        hosts = host_process_snapshot()
        host_blockers = blocking_host_processes(hosts)
        if host_blockers:
            raise DriverError(f"another host build/measurement is active: {host_blockers}")
        return {
            "serial": DESIGNATED_SERIAL,
            "avd": DESIGNATED_AVD,
            "package": PACKAGE_NAME,
            "localApk": str(self.apk),
            "localApkSha256": apk_sha256,
            "installedBaseApk": remote_apk,
            "installedBaseApkSha256": remote_hash,
            "deviceProcessBlockers": [],
            "hostProcessBlockers": [],
            "hostProcessSnapshot": hosts,
            "actions": ["adb shell am force-stop ml.melun.mangav before each trial", "adb shell am instrument", "adb pull"],
            "forbiddenActions": ["build", "install", "cache clear", "cache injection", "readiness wait", "physical display claim"],
        }

    def instrumentation_command(self, pair: int, trial_in_pair: int, mode: str) -> list[str]:
        if mode not in MODES:
            raise DriverError(f"unsupported mode {mode}")
        args = [
            "shell", "am", "instrument", "-w", "-r", "-e", "class", self.validated.test_selector,
            "-e", "startupMode", mode,
            "-e", "startupPair", str(pair),
            "-e", "startupTrial", str(trial_in_pair),
            "-e", "startupSeriesKey", self.validated.series_key,
            "-e", "startupEpisodeKey", self.validated.episode_key,
            "-e", "startupArtifactPrefix", self.validated.artifact_prefix,
        ]
        if self.expected_position is not None:
            args.extend([
                "-e", "startupSavedPageKey", str(self.expected_position["pageKey"]),
                "-e", "startupSavedOffsetUnits", str(self.expected_position["offsetInPageUnits"]),
            ])
        args.append(f"{TEST_PACKAGE}/{TEST_RUNNER}")
        return self.adb.command(*args)

    def remote_dirs(self) -> set[str]:
        result = self.adb.run(
            "shell", "find", self.validated.remote_artifact_root,
            "-mindepth", "1", "-maxdepth", "1", "-type", "d", "-print", check=False,
        )
        if result.returncode != 0:
            return set()
        return {line.strip() for line in _bytes(result.stdout).decode(errors="replace").splitlines() if line.strip()}

    def pull_artifact(self, remote: str, destination: Path) -> Path:
        destination.mkdir(parents=True, exist_ok=False)
        result = self.adb.run("pull", remote, str(destination), check=False, timeout=120)
        if result.returncode != 0:
            raise DriverError(f"adb pull failed for {remote}: {_result_text(result)}")
        expected = destination / Path(remote).name
        if not expected.is_dir():
            raise DriverError(f"adb pull did not produce {expected}")
        return expected

    def _device_instrumentation_blockers(self) -> list[str]:
        result = self.adb.run("shell", "ps", "-A", "-o", "PID,PPID,NAME,ARGS", check=False)
        if result.returncode != 0:
            raise DriverError(f"cannot inspect device instrumentation state: {_result_text(result)}")
        return _device_blockers(_bytes(result.stdout).decode(errors="replace"))

    def _verify_owned_instrumentation_terminated(self, baseline: Sequence[str]) -> dict[str, Any]:
        """Stop only a timed-out test package and prove no new instrumentation remains."""
        deadline = time.monotonic() + 5.0
        forced_test_stop = False
        last: list[str] = []
        while True:
            current = self._device_instrumentation_blockers()
            last = [line for line in current if line not in baseline]
            if not last:
                return {"verified": True, "forcedTestPackageStop": forced_test_stop, "remaining": []}
            owned_markers = (TEST_PACKAGE.lower(), "androidx.test.runner", "startupactivationbenchmark")
            if any(not any(marker in line.lower() for marker in owned_markers) for line in last):
                raise DriverError(f"timed-out cleanup found instrumentation not proven to be ours: {last}")
            if not forced_test_stop:
                self.adb.run("shell", "am", "force-stop", TEST_PACKAGE, check=False, timeout=15)
                forced_test_stop = True
            if time.monotonic() >= deadline:
                break
            time.sleep(0.2)
        raise DriverError(f"timed-out instrumentation did not terminate: {last}")

    def _recover_trial_artifacts(
        self,
        before: set[str],
        staging: Path,
        *,
        pair: int,
        trial_in_pair: int,
        mode: str,
    ) -> tuple[set[str], list[str], list[str], dict[str, Path], list[str]]:
        after = self.remote_dirs()
        new_dirs = sorted(after - before)
        expected_prefix = (
            f"{self.validated.remote_artifact_root}/{self.validated.artifact_prefix}-pair-{pair:02d}-"
            f"trial-{trial_in_pair}-{mode.lower()}-"
        )
        matching = [path for path in new_dirs if path.startswith(expected_prefix)]
        pulled: dict[str, Path] = {}
        pull_errors: list[str] = []
        for index, remote in enumerate(new_dirs):
            destination_name = "pulled" if len(matching) == 1 and remote == matching[0] else f"unexpected-{index:02d}"
            try:
                pulled[remote] = self.pull_artifact(remote, staging / destination_name)
            except DriverError as exc:
                pull_errors.append(f"{remote}: {exc}")
        return after, new_dirs, matching, pulled, pull_errors

    def _run_one_trial(self, pair: int, trial_in_pair: int, mode: str, apk_sha256: str) -> dict[str, Any]:
        before = self.remote_dirs()
        self.adb.run("shell", "am", "force-stop", PACKAGE_NAME)
        baseline_blockers = self._device_instrumentation_blockers()
        if baseline_blockers:
            raise DriverError(f"device instrumentation appeared before owned trial: {baseline_blockers}")
        command = self.instrumentation_command(pair, trial_in_pair, mode)
        staging = self.output / f"pair-{pair:02d}-trial-{trial_in_pair}-{mode.lower()}"
        staging.mkdir(parents=True, exist_ok=False)
        result: CommandResult | None = None
        runner_error: Exception | None = None
        timeout_error: CommandTimeout | None = None
        termination: dict[str, Any] = {"verified": True, "notNeeded": True}
        recovery_error: str | None = None
        after: set[str] = set()
        new_dirs: list[str] = []
        matching: list[str] = []
        pulled: dict[str, Path] = {}
        pull_errors: list[str] = []
        try:
            result = self.runner.run(command, timeout=self.timeout_seconds)
        except CommandTimeout as exc:
            timeout_error = exc
            result = exc.result
        except subprocess.TimeoutExpired as exc:
            result = CommandResult(-124, _bytes(getattr(exc, "stdout", b"")), _bytes(getattr(exc, "stderr", b"")))
            timeout_error = CommandTimeout(command, result, termination_verified=False)
        except Exception as exc:
            runner_error = exc
        finally:
            output_result = result or CommandResult(
                -1,
                _bytes(getattr(runner_error, "stdout", b"")),
                _bytes(getattr(runner_error, "stderr", b"")),
            )
            (staging / "instrumentation.stdout").write_bytes(_bytes(output_result.stdout))
            (staging / "instrumentation.stderr").write_bytes(_bytes(output_result.stderr))
            if timeout_error is not None:
                if timeout_error.termination_verified:
                    try:
                        termination = self._verify_owned_instrumentation_terminated(baseline_blockers)
                    except Exception as exc:
                        termination = {"verified": False, "error": str(exc)}
                else:
                    termination = {
                        "verified": False,
                        "error": "local instrumentation process termination was not verified",
                    }
            if runner_error is None and (timeout_error is None or termination.get("verified") is True):
                try:
                    after, new_dirs, matching, pulled, pull_errors = self._recover_trial_artifacts(
                        before, staging, pair=pair, trial_in_pair=trial_in_pair, mode=mode,
                    )
                except Exception as exc:
                    recovery_error = str(exc)
            _write_json(staging / "instrumentation-status.json", {
                "schemaVersion": 1,
                "returnCode": output_result.returncode,
                "timedOut": timeout_error is not None,
                "termination": termination,
                "remoteDirsBefore": sorted(before),
                "remoteDirsAfter": sorted(after),
                "newRemoteDirs": new_dirs,
                "expectedMatches": matching,
                "pulledRemoteDirs": sorted(pulled),
                "pullErrors": pull_errors,
                "recoveryError": recovery_error,
            })
        if timeout_error is not None:
            detail = str(timeout_error)
            if not termination.get("verified"):
                detail += f"; cleanup failed: {termination.get('error', 'termination unverified')}"
            if recovery_error:
                detail += f"; artifact recovery failed: {recovery_error}"
            raise TrialError(detail)
        if runner_error is not None:
            raise runner_error
        if result is None:
            raise DriverError("instrumentation returned no command result")
        if len(new_dirs) != 1 or len(matching) != 1 or matching[0] not in pulled:
            raise DriverError(
                f"expected one new raw trial directory for pair {pair} trial {trial_in_pair}, got {new_dirs}"
            )
        test_count = validate_instrumentation_result(result)
        artifact_dir = pulled[matching[0]]
        trial_path = artifact_dir / "trial.json"
        if (artifact_dir / "trial-failure.json").is_file():
            raise TrialError(f"device emitted trial-failure.json for pair {pair} trial {trial_in_pair}")
        if not trial_path.is_file():
            raise TrialError(f"device did not emit trial.json for pair {pair} trial {trial_in_pair}")
        trial = _read_json(trial_path)
        validated_trial = validate_trial(
            artifact_dir, trial, self.validated,
            pair=pair, trial_in_pair=trial_in_pair, mode=mode,
            apk_sha256=apk_sha256, expected_position=self.expected_position,
            expected_cache_sha256=self.expected_cache_sha256,
        )
        record = {
            **validated_trial,
            "remoteArtifactDirectory": matching[0],
            "hostArtifactDirectory": str(artifact_dir),
            "instrumentationExitCode": result.returncode,
            "instrumentationTestCount": test_count,
            "instrumentationCommand": command,
            "rawArtifacts": _inventory(artifact_dir),
        }
        _write_json(staging / "driver-record.json", record)
        return record

    def execute(self) -> dict[str, Any]:
        if self.output.exists():
            raise DriverError(f"refusing to reuse existing output directory: {self.output}")
        if not self.apk.is_file():
            raise DriverError(f"local APK does not exist: {self.apk}")
        apk_sha256 = sha256_file(self.apk)
        self.output.mkdir(parents=True, exist_ok=False)
        manifest = build_plan(
            self.validated, pairs=self.pairs, seed=self.seed, expected_position=self.expected_position,
        )
        manifest.update({"state": "PREFLIGHT", "records": []})
        _write_json(self.output / "run.json", manifest)
        try:
            manifest["preflight"] = self.preflight(apk_sha256)
            manifest["state"] = "RUNNING_INITIAL_PAIRS"
            _write_json(self.output / "run.json", manifest)
            records: list[dict[str, Any]] = []
            for pair in range(1, self.pairs + 1):
                for trial_in_pair in (0, 1):
                    mode = self.validated.pair_order[pair - 1][trial_in_pair]
                    record = self._run_one_trial(pair, trial_in_pair, mode, apk_sha256)
                    if not records:
                        # The first valid trial establishes the only state later trials may use.
                        self.expected_position = dict(record["savedPosition"])
                        self.expected_cache_sha256 = str(record["cacheSha256"]).lower()
                        manifest["frozenTrialState"] = {
                            "savedPosition": dict(self.expected_position),
                            "cacheSha256": self.expected_cache_sha256,
                            "boundFrom": {"pair": pair, "trialInPair": trial_in_pair, "mode": mode},
                        }
                    records.append(record)
                    manifest["records"] = records
                    _write_json(self.output / "run.json", manifest)
                if self.pairs == 10 and pair == 5:
                    decision = extension_decision(records, self.validated)
                    manifest["extensionDecision"] = decision
                    if not decision["includesZero"]:
                        manifest["state"] = "COMPLETE_INITIAL_ONLY_EXTENSION_NOT_REQUIRED"
                        manifest["pairsCompleted"] = 5
                        manifest["records"] = records
                        _write_json(self.output / "run.json", manifest)
                        _write_json(self.output / "comparison-manifest.json", {
                            **manifest, "physicalPresentationQualified": False, "corpusCredit": 0,
                        })
                        return manifest
                    manifest["state"] = "RUNNING_EXTENSION_PAIRS"
                    _write_json(self.output / "run.json", manifest)
            if self.pairs == 10:
                manifest["finalTenPairDecision"] = final_ten_pair_decision(records, self.validated)
            manifest.update({
                "state": "COMPLETE",
                "pairsCompleted": len(records) // 2,
                "records": records,
                "physicalPresentationQualified": False,
                "corpusCredit": 0,
            })
            _write_json(self.output / "run.json", manifest)
            _write_json(self.output / "comparison-manifest.json", manifest)
            return manifest
        except Exception as exc:
            _write_json(self.output / "driver-failure.json", {
                "schemaVersion": 1,
                "diagnosticOnly": True,
                "corpusCredit": 0,
                "physicalPresentationQualified": False,
                "state": "FAILED_FIRST_INVALID_OR_MISSING_CONDITION",
                "error": str(exc),
                "records": manifest.get("records", []),
            })
            raise


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract", type=Path, required=True)
    parser.add_argument("--policy", type=Path)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--pairs", type=int, choices=(5, 10), default=5)
    parser.add_argument("--seed", type=int)
    parser.add_argument("--saved-page-key")
    parser.add_argument("--saved-offset-units")
    parser.add_argument("--timeout-seconds", type=float, default=180.0)
    parser.add_argument("--execute", action="store_true", help="run device trials; omit for plan-only validation")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    contract_path = args.contract.resolve()
    policy_path = (args.policy or contract_path.with_name("startup-activation-policy.json")).resolve()
    try:
        contract = _read_json(contract_path)
        policy = _read_json(policy_path)
        validated = validate_contract(contract, policy, contract_path=contract_path, policy_path=policy_path)
        expected_position = parse_position(args.saved_page_key, args.saved_offset_units)
        seed = secrets.randbits(63) if args.seed is None else args.seed
        if not args.execute:
            print(json.dumps({"status": "PLAN_ONLY", **build_plan(
                validated, pairs=args.pairs, seed=seed, expected_position=expected_position,
            )}, indent=2, sort_keys=True))
            return 0
        driver = StartupComparisonDriver(
            validated,
            apk=args.apk,
            output=args.output,
            adb_executable=args.adb,
            pairs=args.pairs,
            seed=seed,
            expected_position=expected_position,
            timeout_seconds=args.timeout_seconds,
        )
        print(json.dumps(driver.execute(), indent=2, sort_keys=True))
        return 0
    except DriverError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
