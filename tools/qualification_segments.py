#!/usr/bin/env python3
"""Bounded, fail-closed verification for one external-display segment.

The Android test owns the checkpoint protocol and the PowerShell runner owns
trace lifecycle.  This module only binds one immutable checkpoint to one
flushed trace/evidence set, invokes the existing display verifier, and emits a
verdict plus an artifact manifest.  The aggregate output is deliberately a
display-verification report; it never establishes the 200-episode
qualification contract.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import subprocess
import sys
import uuid
from collections import Counter
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence

try:  # Running from tools/ (the repository's existing test convention).
    from verify_display_trace import (
        OBSERVABLE_ACCEPTANCE_MODE,
        OBSERVABLE_ACCEPTED_RESULT,
        OBSERVABLE_QUALIFICATION_CLAIM,
        PHYSICAL_METRIC_FIELDS,
        PRESENT_EVENT_ORIGIN,
        PRESENT_EVENT_ROLE,
        UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
        is_observable_policy,
        unavailable_physical_metrics,
        validate_policy,
    )
    from qualification_runner_contract import _validate_observable_display_pass
except ModuleNotFoundError:  # Running as ``python -m tools...``.
    from tools.verify_display_trace import (
        OBSERVABLE_ACCEPTANCE_MODE,
        OBSERVABLE_ACCEPTED_RESULT,
        OBSERVABLE_QUALIFICATION_CLAIM,
        PHYSICAL_METRIC_FIELDS,
        PRESENT_EVENT_ORIGIN,
        PRESENT_EVENT_ROLE,
        UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
        is_observable_policy,
        unavailable_physical_metrics,
        validate_policy,
    )
    from tools.qualification_runner_contract import _validate_observable_display_pass


SCHEMA_VERSION = 1
CHECKPOINT_STATUS = "READY_FOR_EXTERNAL_VERDICT"
HEX_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
SAFE_COMPONENT = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
VERIFIER_PATH = Path(__file__).with_name("verify_display_trace.py").resolve()
REPORT_NAME = "display-verification.json"
MANIFEST_NAME = "segment-manifest.json"
VERDICT_NAME = "external-verdict.json"
RAW_REPORT_NAME = "verifier-report.raw.json"
VERIFIER_TIMEOUT_SECONDS = 90


class SegmentError(ValueError):
    """A checkpoint, artifact, verifier, or aggregate binding is invalid."""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_tree(root: Path) -> str:
    """Hash relative file names and exact file bytes in deterministic order."""
    root = _resolve_directory(root, "evidence directory")
    digest = hashlib.sha256()
    entries = sorted(root.rglob("*"), key=lambda item: item.relative_to(root).as_posix())
    for path in entries:
        if path.is_symlink():
            raise SegmentError(f"evidence tree contains a symlink: {path}")
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix().encode("utf-8")
        content_hash = sha256_file(path).encode("ascii")
        size = str(path.stat().st_size).encode("ascii")
        digest.update(relative + b"\0" + size + b"\0" + content_hash + b"\n")
    return digest.hexdigest()


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise SegmentError(message)


def _hash(value: Any, field: str) -> str:
    _require(isinstance(value, str) and HEX_SHA256.fullmatch(value) is not None,
             f"{field} must be a SHA256 hex string")
    return value.lower()


def _nonempty(value: Any, field: str) -> str:
    _require(isinstance(value, str) and bool(value) and "\x00" not in value,
             f"{field} must be a nonempty string")
    return value


def _safe_component(value: Any, field: str) -> str:
    value = _nonempty(value, field)
    _require(SAFE_COMPONENT.fullmatch(value) is not None,
             f"{field} must be a single path component")
    return value


def _read_bytes(path: Path, label: str) -> bytes:
    try:
        data = path.read_bytes()
    except OSError as error:
        raise SegmentError(f"{label} cannot be read: {error}") from error
    _require(len(data) <= 128 * 1024 * 1024, f"{label} is unexpectedly large")
    return data


def _read_json(path: Path, label: str) -> Any:
    data = _read_bytes(path, label)
    try:
        return json.loads(data.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SegmentError(f"{label} is not valid JSON: {error}") from error


def _resolve_file(value: Path | str, label: str) -> Path:
    original = Path(value)
    _require(not original.is_symlink(), f"{label} must not be a symlink")
    try:
        path = original.resolve(strict=True)
    except OSError as error:
        raise SegmentError(f"{label} cannot be resolved: {error}") from error
    _require(path.is_file(), f"{label} is not a regular file")
    return path


def _resolve_directory(value: Path | str, label: str) -> Path:
    original = Path(value)
    _require(not original.is_symlink(), f"{label} must not be a symlink")
    try:
        path = original.resolve(strict=True)
    except OSError as error:
        raise SegmentError(f"{label} cannot be resolved: {error}") from error
    _require(path.is_dir(), f"{label} is not a directory")
    return path


def _json_bytes(document: Any) -> bytes:
    return (json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True,
                       allow_nan=False) + "\n").encode("utf-8")


def _write_once(path: Path, document: Any) -> None:
    """Atomically create a result file and refuse to overwrite an old result."""
    _require(not path.exists(), f"refusing to overwrite immutable artifact: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_bytes(_json_bytes(document) if not isinstance(document, bytes) else document)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def _replace_document(path: Path, document: Any) -> None:
    """Replace a verifier report only after preserving its original bytes."""
    _require(path.exists(), f"cannot replace missing artifact: {path}")
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_bytes(_json_bytes(document))
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def _write_text_once(path: Path, data: str | bytes) -> None:
    _require(not path.exists(), f"refusing to overwrite immutable artifact: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_bytes(data.encode("utf-8") if isinstance(data, str) else data)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def _load_checkpoint(path: Path, expected_run_id: str, expected_attempt: str,
                     expected_policy: str, expected_nonce: str | None = None) -> tuple[dict[str, Any], str, bytes]:
    raw = _read_bytes(path, "checkpoint")
    try:
        value = json.loads(raw.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SegmentError(f"Malformed checkpoint: {error}") from error
    _require(isinstance(value, dict), "checkpoint must be an object")
    _require(type(value.get("schema")) is int and value["schema"] == SCHEMA_VERSION,
             "unsupported checkpoint schema")
    for key in ("runId", "sampleKey", "attemptSha256", "policySha256", "checkpointNonce", "status"):
        _require(key in value, f"checkpoint is missing {key}")
    _require(value.get("runId") == expected_run_id, "checkpoint runId does not match this attempt")
    _safe_component(value.get("sampleKey"), "checkpoint sampleKey")
    _require(_hash(value.get("attemptSha256"), "checkpoint attemptSha256") == expected_attempt,
             "checkpoint attemptSha256 does not match this attempt")
    _require(_hash(value.get("policySha256"), "checkpoint policySha256") == expected_policy,
             "checkpoint policySha256 does not match the frozen policy")
    nonce = _safe_component(value.get("checkpointNonce"), "checkpoint checkpointNonce")
    if expected_nonce is not None:
        _require(nonce == expected_nonce, "checkpoint nonce is stale or unexpected")
    _require(value.get("status") == CHECKPOINT_STATUS,
             f"checkpoint status must be {CHECKPOINT_STATUS}")
    return value, sha256_bytes(raw), raw


def _load_single_collection(evidence: Path, expected_sample_key: str) -> tuple[Path, dict[str, Any]]:
    collections: list[Path] = []
    for path in evidence.rglob("collection.json"):
        if path.is_symlink():
            raise SegmentError(f"collection artifact is a symlink: {path}")
        if path.is_file():
            collections.append(path)
    _require(len(collections) == 1,
             f"evidence directory must contain exactly one collection.json (found {len(collections)})")
    collection_path = collections[0].resolve()
    collection = _read_json(collection_path, "collection.json")
    _require(isinstance(collection, dict), "collection.json must be an object")
    _require(collection.get("sampleKey") == expected_sample_key,
             "collection sampleKey does not match the checkpoint")
    return collection_path, collection


def _policy_document(policy_path: Path, expected_sha256: str) -> tuple[dict[str, Any], str]:
    raw = _read_bytes(policy_path, "policy")
    actual = sha256_bytes(raw)
    _require(actual == expected_sha256, "policy bytes do not match checkpoint policySha256")
    value = _read_json(policy_path, "policy")
    try:
        policy = validate_policy(value)
    except (TypeError, ValueError) as error:
        raise SegmentError(f"frozen policy is invalid: {error}") from error
    _require(isinstance(policy, dict), "frozen policy must be an object")
    return policy, actual


def _is_inside(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _prepare_output(output: Path, evidence: Path, *files: Path) -> Path:
    output = output.expanduser().resolve()
    _require(not _is_inside(output, evidence),
             "segment output directory must not be inside the evidence directory")
    for path in files:
        _require(output != path, f"segment output directory aliases {path}")
    output.mkdir(parents=True, exist_ok=True)
    for name in (REPORT_NAME, MANIFEST_NAME, VERDICT_NAME):
        _require(not (output / name).exists(), f"segment output already contains {name}")
    return output


def _error_report(sample_key: str | None, policy: dict[str, Any] | None, reason: str) -> dict[str, Any]:
    observable = bool(policy and is_observable_policy(policy))
    return {
        "schemaVersion": SCHEMA_VERSION,
        "sampleKey": sample_key,
        "passed": False,
        "displayEvidenceComplete": False,
        "observableEvidenceComplete": False,
        "result": "VERIFIER_ERROR" if not observable else "OBSERVABLE_RENDER_V1_REJECTED",
        "acceptanceMode": OBSERVABLE_ACCEPTANCE_MODE if observable else None,
        "physicalPresentationTiming": UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING if observable else None,
        "exactPhysicalPresentationTimeVerified": False,
        "displayCorrelationComplete": False,
        "coverageComplete": False,
        "timingComplete": False,
        "noAutoJumpEvidenceComplete": False,
        "physicalTimingComplete": False,
        "requiresCalibration": True,
        "measurementUncertainty": {
            "presentEventOrigin": PRESENT_EVENT_ORIGIN,
            "presentEventRole": PRESENT_EVENT_ROLE,
            "physicalPresentationTiming": (UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING
                                            if observable else "INDEPENDENT_CALIBRATION_REQUIRED"),
        },
        "physicalMetrics": unavailable_physical_metrics(),
        "violations": [{"gate": "segment-helper", "reason": reason}],
        "violationCounts": {"segment-helper": 1},
        "series": [],
        "qualificationClaim": (OBSERVABLE_QUALIFICATION_CLAIM if observable else
                                "NONE; this report alone never establishes 200-episode completion"),
    }


def _report_policy_sha(report: dict[str, Any], policy_sha: str) -> None:
    if report.get("policySha256") is not None:
        _require(_hash(report.get("policySha256"), "display report policySha256") == policy_sha,
                 "display report policySha256 does not match the frozen policy")


def _validate_pass_report(report: dict[str, Any], sample_key: str, policy: dict[str, Any],
                          policy_sha: str, trace_sha: str, verifier_sha: str,
                          exit_code: int | None) -> None:
    _require(isinstance(report.get("passed"), bool), "display report passed must be boolean")
    _report_policy_sha(report, policy_sha)
    if report.get("passed") is not True:
        _require(exit_code != 0, "verifier exit code and report passed value disagree")
        series = report.get("series")
        if isinstance(series, list) and len(series) == 1:
            _require(isinstance(series[0], dict) and series[0].get("sampleKey") == sample_key,
                     "failed display report series sampleKey does not match checkpoint")
        return
    series = report.get("series")
    _require(isinstance(series, list) and len(series) == 1,
             "segment display report must contain exactly one series")
    _require(isinstance(series[0], dict) and series[0].get("sampleKey") == sample_key,
             "segment display report series sampleKey does not match checkpoint")
    _require(exit_code == 0, "verifier reported pass without a zero exit code")
    _require(report.get("policySha256") is not None,
             "display verifier pass omitted policySha256")
    _require(_hash(report.get("policySha256"), "display report policySha256") == policy_sha,
             "display verifier pass policySha256 does not match the frozen policy")
    _require(_hash(report.get("traceSha256"), "display report traceSha256") == trace_sha,
             "display verifier pass traceSha256 does not match the flushed trace")
    _require(_hash(report.get("verifierSha256"), "display report verifierSha256") == verifier_sha,
             "display verifier pass verifierSha256 does not match the verifier bytes")
    if is_observable_policy(policy):
        try:
            _validate_observable_display_pass(report, series, policy)
        except Exception as error:
            raise SegmentError(str(error)) from error
    else:
        _require(report.get("displayEvidenceComplete") is True and
                 report.get("physicalTimingComplete") is True and
                 report.get("requiresCalibration") is False,
                 "physical pass lacks complete physical disclosure")
    segment = series[0]
    _require(segment.get("passed") is True and segment.get("observableEvidenceComplete") is True,
             "display verifier series did not pass")
    _report_policy_sha(segment, policy_sha)


def _mark_helper_failure(report: dict[str, Any], reason: str, policy: dict[str, Any],
                         sample_key: str | None) -> dict[str, Any]:
    failed = copy.deepcopy(report)
    failed["schemaVersion"] = SCHEMA_VERSION
    failed["sampleKey"] = sample_key
    failed["passed"] = False
    failed["observableEvidenceComplete"] = False
    failed["result"] = ("OBSERVABLE_RENDER_V1_REJECTED" if is_observable_policy(policy)
                         else "VERIFIER_ERROR")
    if is_observable_policy(policy):
        failed["acceptanceMode"] = OBSERVABLE_ACCEPTANCE_MODE
        failed["physicalPresentationTiming"] = UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING
        failed["exactPhysicalPresentationTimeVerified"] = False
        failed["physicalTimingComplete"] = False
        failed["requiresCalibration"] = True
        failed["qualificationClaim"] = OBSERVABLE_QUALIFICATION_CLAIM
        failed["physicalMetrics"] = unavailable_physical_metrics()
        failed["measurementUncertainty"] = {
            "presentEventOrigin": PRESENT_EVENT_ORIGIN,
            "presentEventRole": PRESENT_EVENT_ROLE,
            "physicalPresentationTiming": UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
        }
    violations = failed.get("violations")
    if not isinstance(violations, list):
        violations = []
    violations.append({"gate": "segment-helper", "reason": reason})
    failed["violations"] = violations
    counts = failed.get("violationCounts")
    if not isinstance(counts, dict):
        counts = {}
    counts["segment-helper"] = int(counts.get("segment-helper", 0)) + 1
    failed["violationCounts"] = counts
    series = failed.get("series")
    if isinstance(series, list):
        for item in series:
            if isinstance(item, dict) and item.get("sampleKey") == sample_key:
                item["passed"] = False
                item["observableEvidenceComplete"] = False
                item.setdefault("violations", []).append({"gate": "segment-helper", "reason": reason})
                item_counts = item.get("violationCounts")
                if not isinstance(item_counts, dict):
                    item_counts = {}
                item_counts["segment-helper"] = int(item_counts.get("segment-helper", 0)) + 1
                item["violationCounts"] = item_counts
    return failed


def _run_verifier(command: Sequence[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=str(VERIFIER_PATH.parent), capture_output=True,
                          text=True, encoding="utf-8", errors="replace", check=False,
                          timeout=VERIFIER_TIMEOUT_SECONDS)


def verify_segment(
    checkpoint: Path,
    expected_run_id: str,
    expected_attempt_sha256: str,
    expected_policy_sha256: str,
    trace: Path,
    evidence_directory: Path,
    policy: Path,
    output_directory: Path,
    *,
    expected_checkpoint_nonce: str | None = None,
    verifier_path: Path | None = None,
    verifier_runner: Callable[[Sequence[str]], Any] | None = None,
) -> dict[str, Any]:
    """Verify one flushed segment and atomically emit its verdict/manifest."""
    expected_attempt = _hash(expected_attempt_sha256, "expected attemptSha256")
    expected_policy = _hash(expected_policy_sha256, "expected policySha256")
    expected_run_id = _nonempty(expected_run_id, "expected runId")
    checkpoint_path = _resolve_file(checkpoint, "checkpoint")
    trace_path = _resolve_file(trace, "flushed trace")
    evidence_path = _resolve_directory(evidence_directory, "evidence directory")
    policy_path = _resolve_file(policy, "policy")
    verifier = _resolve_file(verifier_path or VERIFIER_PATH, "display verifier")

    checkpoint_value, checkpoint_sha, checkpoint_raw = _load_checkpoint(
        checkpoint_path, expected_run_id, expected_attempt, expected_policy, expected_checkpoint_nonce)
    sample_key = checkpoint_value["sampleKey"]
    collection_path, _ = _load_single_collection(evidence_path, sample_key)
    policy_value, policy_sha = _policy_document(policy_path, expected_policy)
    output = _prepare_output(Path(output_directory), evidence_path,
                             checkpoint_path, trace_path, policy_path, verifier)
    report_path = output / REPORT_NAME
    manifest_path = output / MANIFEST_NAME
    verdict_path = output / VERDICT_NAME

    before = {
        "traceSha256": sha256_file(trace_path),
        "evidenceSha256": sha256_tree(evidence_path),
        "policySha256": sha256_file(policy_path),
        "verifierSha256": sha256_file(verifier),
    }
    _require(before["policySha256"] == expected_policy, "policy changed during checkpoint validation")
    command = [sys.executable, str(verifier), "--trace", str(trace_path),
               "--evidence-directory", str(evidence_path), "--output", str(report_path),
               "--policy", str(policy_path)]
    runner = verifier_runner or _run_verifier
    exit_code: int | None = None
    runner_error: str | None = None
    completed: Any = None
    try:
        completed = runner(command)
        exit_code = completed.returncode
        _require(isinstance(exit_code, int), "display verifier returned no integer exit code")
        stdout = getattr(completed, "stdout", "")
        stderr = getattr(completed, "stderr", "")
        _write_text_once(output / "verifier.stdout.txt", stdout if isinstance(stdout, (str, bytes)) else str(stdout))
        _write_text_once(output / "verifier.stderr.txt", stderr if isinstance(stderr, (str, bytes)) else str(stderr))
    except Exception as error:  # The report and failed verdict still need to be durable.
        runner_error = f"{type(error).__name__}: {error}"
        stdout = getattr(error, "stdout", "")
        stderr = getattr(error, "stderr", "")
        _write_text_once(output / "verifier.stdout.txt",
                         stdout if isinstance(stdout, (str, bytes)) else str(stdout or ""))
        _write_text_once(output / "verifier.stderr.txt",
                         stderr if isinstance(stderr, (str, bytes)) else str(stderr or ""))

    errors: list[str] = []
    if runner_error:
        errors.append(f"verifier invocation failed: {runner_error}")

    current_checkpoint_raw = _read_bytes(checkpoint_path, "checkpoint")
    if current_checkpoint_raw != checkpoint_raw:
        errors.append("checkpoint bytes changed while the segment was being verified")
    after = {
        "traceSha256": sha256_file(trace_path),
        "evidenceSha256": sha256_tree(evidence_path),
        "policySha256": sha256_file(policy_path),
        "verifierSha256": sha256_file(verifier),
    }
    for field in before:
        if before[field] != after[field]:
            errors.append(f"{field} changed while the segment was being verified")

    report: dict[str, Any]
    raw_report: bytes | None = None
    if report_path.exists():
        raw_report = report_path.read_bytes()
        try:
            loaded = json.loads(raw_report.decode("utf-8-sig"))
            _require(isinstance(loaded, dict), "display verifier report must be an object")
            report = loaded
        except (UnicodeDecodeError, json.JSONDecodeError, SegmentError) as error:
            _write_text_once(output / RAW_REPORT_NAME, raw_report)
            report = _error_report(sample_key, policy_value, f"invalid verifier report: {error}")
            errors.append(f"invalid verifier report: {error}")
    else:
        report = _error_report(sample_key, policy_value,
                               runner_error or "display verifier did not produce a report")
        errors.append("display verifier report was not produced")

    try:
        _require(report.get("sampleKey") in (None, sample_key),
                 "display verifier report top-level sampleKey does not match checkpoint")
        _validate_pass_report(report, sample_key, policy_value, expected_policy,
                              after["traceSha256"], after["verifierSha256"], exit_code)
    except SegmentError as error:
        errors.append(str(error))

    if errors:
        if raw_report is None and report_path.exists():
            raw_report = report_path.read_bytes()
        if raw_report is not None:
            # Keep the verifier's raw bytes before replacing the canonical report.
            if not (output / RAW_REPORT_NAME).exists():
                _write_text_once(output / RAW_REPORT_NAME, raw_report)
        report = _mark_helper_failure(report, "; ".join(errors), policy_value, sample_key)
        if report_path.exists():
            _replace_document(report_path, report)
        else:
            _write_once(report_path, report)
    elif not report_path.exists():
        _write_once(report_path, report)

    report_sha = sha256_file(report_path)
    evidence_sha = after["evidenceSha256"]
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "runId": expected_run_id,
        "sampleKey": sample_key,
        "attemptSha256": expected_attempt,
        "policySha256": expected_policy,
        "checkpointNonce": checkpoint_value["checkpointNonce"],
        "checkpointSha256": checkpoint_sha,
        "passed": bool(not errors and report.get("passed") is True),
        "verifierExitCode": exit_code,
        "traceSha256": after["traceSha256"],
        "evidenceSha256": evidence_sha,
        "reportSha256": report_sha,
        "verifierSha256": after["verifierSha256"],
        "checkpointPath": str(checkpoint_path),
        "tracePath": str(trace_path),
        "evidenceDirectory": str(evidence_path),
        "reportPath": str(report_path),
        "policyPath": str(policy_path),
        "verifierPath": str(verifier),
        "errors": errors,
    }
    _write_once(manifest_path, manifest)
    verdict = {
        "schema": SCHEMA_VERSION,
        "runId": expected_run_id,
        "sampleKey": sample_key,
        "attemptSha256": expected_attempt,
        "policySha256": expected_policy,
        "checkpointNonce": checkpoint_value["checkpointNonce"],
        "checkpointSha256": checkpoint_sha,
        "passed": manifest["passed"],
    }
    _write_once(verdict_path, verdict)
    return {
        "passed": manifest["passed"],
        "report": report,
        "manifest": manifest,
        "verdict": verdict,
        "reportPath": report_path,
        "manifestPath": manifest_path,
        "verdictPath": verdict_path,
        "collectionPath": collection_path,
    }


def _validate_segment_manifest(path: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    manifest = _read_json(path, f"segment manifest {path}")
    _require(isinstance(manifest, dict), "segment manifest must be an object")
    required = (
        "schemaVersion", "runId", "sampleKey", "attemptSha256", "policySha256", "checkpointNonce",
        "checkpointSha256", "passed", "traceSha256", "evidenceSha256", "reportSha256",
        "verifierSha256", "checkpointPath", "tracePath", "evidenceDirectory", "reportPath",
        "policyPath", "verifierPath",
    )
    for key in required:
        _require(key in manifest, f"segment manifest is missing {key}")
    _require(type(manifest.get("schemaVersion")) is int and manifest["schemaVersion"] == SCHEMA_VERSION,
             "unsupported segment manifest schema")
    run_id = _nonempty(manifest.get("runId"), "segment manifest runId")
    sample_key = _safe_component(manifest.get("sampleKey"), "segment manifest sampleKey")
    _hash(manifest.get("attemptSha256"), "segment manifest attemptSha256")
    _hash(manifest.get("policySha256"), "segment manifest policySha256")
    _safe_component(manifest.get("checkpointNonce"), "segment manifest checkpointNonce")
    for key in ("checkpointSha256", "traceSha256", "evidenceSha256", "reportSha256", "verifierSha256"):
        _hash(manifest.get(key), f"segment manifest {key}")
    _require(isinstance(manifest.get("passed"), bool), "segment manifest passed must be boolean")

    checkpoint = _resolve_file(manifest["checkpointPath"], "manifest checkpoint")
    trace = _resolve_file(manifest["tracePath"], "manifest trace")
    evidence = _resolve_directory(manifest["evidenceDirectory"], "manifest evidence")
    report_path = _resolve_file(manifest["reportPath"], "manifest report")
    policy_path = _resolve_file(manifest["policyPath"], "manifest policy")
    verifier = _resolve_file(manifest["verifierPath"], "manifest verifier")
    _require(sha256_file(checkpoint) == manifest["checkpointSha256"], "checkpoint artifact was mutated")
    _require(sha256_file(trace) == manifest["traceSha256"], "trace artifact was mutated")
    _require(sha256_tree(evidence) == manifest["evidenceSha256"], "evidence artifact was mutated")
    _require(sha256_file(report_path) == manifest["reportSha256"], "report artifact was mutated")
    _require(sha256_file(policy_path) == manifest["policySha256"], "policy artifact was mutated")
    _require(sha256_file(verifier) == manifest["verifierSha256"], "verifier artifact was mutated")
    checkpoint_value, checkpoint_sha, _ = _load_checkpoint(
        checkpoint, run_id, manifest["attemptSha256"], manifest["policySha256"], manifest["checkpointNonce"])
    _require(checkpoint_sha == manifest["checkpointSha256"], "manifest checkpoint hash is stale")
    _load_single_collection(evidence, sample_key)
    policy_value, policy_sha = _policy_document(policy_path, manifest["policySha256"])
    report = _read_json(report_path, "segment report")
    _require(isinstance(report, dict), "segment report must be an object")
    _require(report.get("policySha256") in (None, policy_sha),
             "segment report policy hash does not match manifest")
    series = report.get("series")
    _require(isinstance(series, list), "segment report series must be an array")
    if len(series) == 1:
        _require(isinstance(series[0], dict) and series[0].get("sampleKey") == sample_key,
                 "segment report series sampleKey does not match manifest")
    else:
        _require(not manifest["passed"] and not series,
                 "segment report must contain one matching series unless it failed before series verification")
    _require(report.get("passed") is manifest["passed"],
             "segment manifest passed value disagrees with report")
    if manifest["passed"]:
        _validate_pass_report(report, sample_key, policy_value, policy_sha,
                              manifest["traceSha256"], manifest["verifierSha256"], 0)
    return manifest, report


def aggregate_segments(manifests: Iterable[Path], output: Path | None = None) -> dict[str, Any]:
    """Validate ordered immutable segment manifests and combine their reports."""
    paths = [Path(path).expanduser().resolve() for path in manifests]
    _require(bool(paths), "aggregate requires at least one segment manifest")
    records: list[tuple[dict[str, Any], dict[str, Any]]] = []
    sample_keys: set[str] = set()
    for path in paths:
        manifest, report = _validate_segment_manifest(_resolve_file(path, "segment manifest"))
        _require(manifest["sampleKey"] not in sample_keys,
                 f"aggregate contains duplicate sampleKey {manifest['sampleKey']}")
        sample_keys.add(manifest["sampleKey"])
        records.append((manifest, report))

    first_manifest, first_report = records[0]
    binding_fields = ("runId", "attemptSha256", "policySha256", "verifierSha256")
    for manifest, _ in records[1:]:
        for field in binding_fields:
            _require(manifest[field] == first_manifest[field],
                     f"aggregate {field} differs between segment manifests")

    common_fields = (
        "acceptanceMode", "physicalPresentationTiming", "requiresCalibration",
        "displayEvidenceComplete", "physicalTimingComplete", "qualificationClaim",
    )
    for _, report in records[1:]:
        for field in common_fields:
            _require(report.get(field) == first_report.get(field),
                     f"aggregate report field {field} differs between segments")
        _require(report.get("physicalMetrics") == first_report.get("physicalMetrics"),
                 "aggregate physicalMetrics differ between segments")
        _require(report.get("measurementUncertainty") == first_report.get("measurementUncertainty"),
                 "aggregate measurementUncertainty differs between segments")

    reports = [report for _, report in records]
    all_passed = all(manifest["passed"] and report.get("passed") is True
                     for manifest, report in records)
    violations: list[Any] = []
    counts: Counter[str] = Counter()
    for report in reports:
        if isinstance(report.get("violations"), list):
            violations.extend(copy.deepcopy(report["violations"]))
        if isinstance(report.get("violationCounts"), dict):
            for key, value in report["violationCounts"].items():
                if isinstance(value, int) and not isinstance(value, bool):
                    counts[key] += value
    observable = first_report.get("acceptanceMode") == OBSERVABLE_ACCEPTANCE_MODE
    aggregate = {
        "schemaVersion": SCHEMA_VERSION,
        "passed": all_passed and not violations and not counts,
        "displayEvidenceComplete": all(report.get("displayEvidenceComplete") is True for report in reports),
        "observableEvidenceComplete": all(report.get("observableEvidenceComplete") is True for report in reports),
        "result": (OBSERVABLE_ACCEPTED_RESULT if observable and all_passed and not violations and not counts
                   else "OBSERVABLE_RENDER_V1_REJECTED" if observable else
                   "PHYSICAL_RENDER_REJECTED"),
        "acceptanceMode": first_report.get("acceptanceMode"),
        "physicalPresentationTiming": first_report.get("physicalPresentationTiming"),
        "exactPhysicalPresentationTimeVerified": all(
            report.get("exactPhysicalPresentationTimeVerified") is True for report in reports),
        "displayCorrelationComplete": all(report.get("displayCorrelationComplete") is True for report in reports),
        "coverageComplete": all(report.get("coverageComplete") is True for report in reports),
        "timingComplete": all(report.get("timingComplete") is True for report in reports),
        "noAutoJumpEvidenceComplete": all(report.get("noAutoJumpEvidenceComplete") is True for report in reports),
        "physicalTimingComplete": first_report.get("physicalTimingComplete"),
        "requiresCalibration": first_report.get("requiresCalibration"),
        "traceSha256": None,
        "verifierSha256": first_manifest["verifierSha256"],
        "policySha256": first_manifest["policySha256"],
        "identitySnapshotSha256": first_report.get("identitySnapshotSha256"),
        "measurementUncertainty": copy.deepcopy(first_report.get("measurementUncertainty")),
        "physicalMetrics": copy.deepcopy(first_report.get("physicalMetrics")),
        "violations": violations,
        "violationCounts": dict(counts),
        "series": [
            copy.deepcopy(report["series"][0]) if len(report.get("series", [])) == 1 else {
                "sampleKey": manifest["sampleKey"], "passed": False,
                "observableEvidenceComplete": False,
                "violations": copy.deepcopy(report.get("violations", [])),
                "violationCounts": copy.deepcopy(report.get("violationCounts", {})),
            }
            for manifest, report in records
        ],
        "qualificationClaim": first_report.get("qualificationClaim"),
        "segmentOnly": True,
        "qualificationContractRequired": True,
        "segmentCount": len(records),
        "segmentTraceSha256": [manifest["traceSha256"] for manifest, _ in records],
        "segmentEvidenceSha256": [manifest["evidenceSha256"] for manifest, _ in records],
    }
    if output is not None:
        output_path = Path(output).expanduser().resolve()
        _write_once(output_path, aggregate)
    return aggregate


def _verify_cli(arguments: argparse.Namespace) -> int:
    result = verify_segment(
        Path(arguments.checkpoint), arguments.run_id, arguments.attempt_sha256,
        arguments.policy_sha256, Path(arguments.trace), Path(arguments.evidence_directory),
        Path(arguments.policy), Path(arguments.output_directory),
        expected_checkpoint_nonce=arguments.checkpoint_nonce,
    )
    print(json.dumps({"passed": result["passed"], "verdict": str(result["verdictPath"]),
                      "manifest": str(result["manifestPath"]), "report": str(result["reportPath"])},
                     ensure_ascii=False))
    return 0 if result["passed"] else 1


def _aggregate_cli(arguments: argparse.Namespace) -> int:
    result = aggregate_segments([Path(path) for path in arguments.manifest], Path(arguments.output))
    print(json.dumps({"passed": result["passed"], "output": str(Path(arguments.output).resolve()),
                      "segmentCount": result["segmentCount"]}, ensure_ascii=False))
    return 0 if result["passed"] else 1


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    verify_parser = subparsers.add_parser("verify-segment")
    verify_parser.add_argument("--checkpoint", type=Path, required=True)
    verify_parser.add_argument("--run-id", required=True)
    verify_parser.add_argument("--attempt-sha256", required=True)
    verify_parser.add_argument("--policy-sha256", required=True)
    verify_parser.add_argument("--checkpoint-nonce")
    verify_parser.add_argument("--trace", type=Path, required=True)
    verify_parser.add_argument("--evidence-directory", type=Path, required=True)
    verify_parser.add_argument("--policy", type=Path, required=True)
    verify_parser.add_argument("--output-directory", type=Path, required=True)
    verify_parser.set_defaults(handler=_verify_cli)

    aggregate_parser = subparsers.add_parser("aggregate")
    aggregate_parser.add_argument("--manifest", action="append", nargs="+", required=True)
    aggregate_parser.add_argument("--output", type=Path, required=True)
    aggregate_parser.set_defaults(handler=_aggregate_cli)
    arguments = parser.parse_args(list(argv) if argv is not None else None)
    try:
        # argparse's append+nargs form is convenient for PowerShell but yields
        # nested lists; flatten it before dispatch.
        if arguments.command == "aggregate":
            arguments.manifest = [item for group in arguments.manifest for item in group]
        return arguments.handler(arguments)
    except (SegmentError, OSError, ValueError) as error:
        print(json.dumps({"passed": False, "error": f"{type(error).__name__}: {error}"}),
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
