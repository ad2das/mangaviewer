"""Preserve identifiable historical failed samples as mandatory regressions, never corpus credit."""
import argparse
import json
from pathlib import Path


MANDATORY_ROLE = "MANDATORY_REGRESSION_NO_CORPUS_CREDIT"
SINGLE_EPISODE_ROLE = "MANDATORY_SINGLE_EPISODE_NO_CORPUS_CREDIT"
SINGLE_EPISODE_CLASSIFICATION = "SINGLE_EPISODE_REGRESSION"
SINGLE_EPISODE_DEVICE_FAILURE = "SINGLE_EPISODE_DEVICE_FAILURE"
SINGLE_EPISODE_DISPLAY_FAILURE = "SINGLE_EPISODE_DISPLAY_FAILURE"
CALIBRATION_ONLY_CLASSIFICATION = "CALIBRATION_ONLY_NO_CORPUS_CREDIT"


def normalize_sample(sample):
    """Validate and canonicalize one exact five-episode sample identity."""
    if not isinstance(sample, dict):
        raise ValueError("sample must be an object")
    source = sample.get("source")
    kind = sample.get("kind")
    series = sample.get("seriesKey")
    title = sample.get("title")
    episodes = sample.get("episodes")
    if source not in ("ntk", "wfwf") or kind not in ("COMIC", "WEBTOON"):
        raise ValueError("sample has an unidentified source or content kind")
    if not isinstance(series, str) or not series.strip():
        raise ValueError("sample has no exact series identity")
    if not isinstance(title, str) or not title.strip():
        raise ValueError("sample has no work title")
    if not isinstance(episodes, list) or len(episodes) != 5:
        raise ValueError("complete five-episode chain is unavailable")
    normalized_episodes = []
    for episode in episodes:
        if isinstance(episode, str):
            key = episode
            episode_title = episode
        elif isinstance(episode, dict):
            key = episode.get("key")
            episode_title = episode.get("title", key)
        else:
            raise ValueError("episode identity is malformed")
        if not isinstance(key, str) or not key.strip():
            raise ValueError("episode identity is missing")
        if not isinstance(episode_title, str) or not episode_title.strip():
            raise ValueError("episode title is missing")
        normalized_episodes.append({"key": key, "title": episode_title})
    if len({episode["key"] for episode in normalized_episodes}) != 5:
        raise ValueError("duplicate episode identities")
    normalized = dict(sample)
    normalized["source"] = source
    normalized["kind"] = kind
    normalized["seriesKey"] = series
    normalized["title"] = title
    normalized["episodes"] = normalized_episodes
    normalized["refreshEpisodeTitlesFromExactLiveIds"] = True
    return normalized


def sample_key(sample):
    sample = normalize_sample(sample)
    return (sample["source"], sample["kind"], sample["seriesKey"],
            tuple(episode["key"] for episode in sample["episodes"]))


def from_outcome(outcome, path):
    if not isinstance(outcome, dict):
        raise ValueError("outcome must be an object")
    if "sample" in outcome:
        return normalize_sample(outcome["sample"])
    series = outcome.get("seriesKey", "")
    if not isinstance(series, str):
        raise ValueError("series identity is malformed")
    source = outcome.get("source")
    if not source:
        source = "ntk" if series.startswith(("/manhwa/", "/webtoon/")) else (
            "wfwf" if series.startswith(("comic:", "webtoon:")) else None)
    kind = outcome.get("kind")
    if not kind:
        kind = "COMIC" if series.startswith(("/manhwa/", "comic:")) else (
            "WEBTOON" if series.startswith(("/webtoon/", "webtoon:")) else None)
    chain = outcome.get("chain", outcome.get("episodes"))
    if source not in ("ntk", "wfwf") or kind not in ("COMIC", "WEBTOON"):
        raise ValueError("unidentified source or content kind")
    if not isinstance(chain, list) or len(chain) != 5:
        raise ValueError("complete five-episode chain is unavailable")
    episodes = [{"key": item, "title": item} if isinstance(item, str) else item for item in chain]
    return normalize_sample({"source": source, "kind": kind, "seriesKey": series,
                             "title": outcome.get("title"), "episodes": episodes})


def _merge_provenance(target, source):
    history = target.setdefault("provenance", [])
    for item in source.get("provenance", []):
        if item not in history:
            history.append(item)


def import_failures(roots, existing):
    merged = {}
    for item in existing:
        if not isinstance(item, dict):
            raise ValueError("existing mandatory regression is not an object")
        if item.get("role", MANDATORY_ROLE) != MANDATORY_ROLE:
            raise ValueError("existing regression has an invalid role")
        sample = normalize_sample(item.get("sample"))
        key = sample_key(sample)
        normalized = dict(item)
        normalized["sample"] = sample
        normalized["role"] = MANDATORY_ROLE
        normalized.setdefault("provenance", [])
        if not isinstance(normalized["provenance"], list):
            raise ValueError("existing regression provenance is malformed")
        if key in merged:
            _merge_provenance(merged[key], normalized)
        else:
            merged[key] = normalized
    unresolved = []
    paths = sorted({path.resolve() for root in roots for path in root.rglob("*outcomes.json")})
    for path in paths:
        try:
            outcomes = json.loads(path.read_text(encoding="utf-8-sig"))
            if not isinstance(outcomes, list):
                raise ValueError("outcome file is not an array")
            for index, item in enumerate(outcomes):
                if not isinstance(item, dict):
                    raise ValueError(f"outcome {index} is not an object")
                # Deferred collection is not a historical failure without an actual failure record.
                if item.get("passed") is not False or "failure" not in item or item.get("failure") is None:
                    continue
                if not isinstance(item["failure"], str) or not item["failure"].strip():
                    unresolved.append({"artifact": str(path), "index": index,
                                       "reason": "failed outcome has no nonblank failure reason"})
                    continue
                try:
                    if item.get("role") == SINGLE_EPISODE_ROLE:
                        _normalize_single_failure({"singleEpisodeRegression": item, "failure": item["failure"]})
                        # The single-lane importer retains these exact identities and provenance.
                        continue
                    sample = from_outcome(item, path)
                    provenance = {"artifact": str(path), "index": index, "failure": item["failure"]}
                    target = merged.setdefault(sample_key(sample), {
                        "sample": sample, "role": MANDATORY_ROLE, "provenance": []})
                    _merge_provenance(target, {"provenance": [provenance]})
                except (KeyError, TypeError, ValueError) as error:
                    unresolved.append({"artifact": str(path), "index": index, "reason": str(error)})
        except (OSError, ValueError, TypeError) as error:
            unresolved.append({"artifact": str(path), "reason": str(error)})
    return list(merged.values()), unresolved


def _required_text(value, field):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} is missing or malformed")
    return value


def _wfwf_identity(payload, source_field):
    if payload.get(source_field) != "wfwf":
        raise ValueError("artifact is not a WFWF record")
    series = _required_text(payload.get("seriesKey"), "seriesKey")
    episode = _required_text(payload.get("episodeKey"), "episodeKey")
    if series.startswith("comic:"):
        kind = "COMIC"
    elif series.startswith("webtoon:"):
        kind = "WEBTOON"
    else:
        raise ValueError("WFWF series identity has no recognized content kind")
    return {
        "source": "wfwf",
        "kind": kind,
        "seriesKey": series,
        "episodeKey": episode,
    }


def _single_episode_key(identity):
    return (identity["source"], identity["kind"], identity["seriesKey"],
            identity["episodeKey"])


def _add_single_episode_failure(merged, identity, provenance):
    key = _single_episode_key(identity)
    target = merged.setdefault(key, {
        **identity,
        "role": SINGLE_EPISODE_ROLE,
        "classification": SINGLE_EPISODE_CLASSIFICATION,
        "provenance": [],
    })
    _merge_provenance(target, {"provenance": [provenance]})


def _summary_failure_reason(violations):
    if violations is None:
        return None
    if isinstance(violations, list):
        if not violations:
            return None
        return violations
    if isinstance(violations, str) and violations.strip():
        return violations
    raise ValueError("WFWF summary failure violations are malformed")


def _find_companion(path, filename):
    for parent in (path.parent, *path.parents):
        candidate = parent / filename
        if candidate.is_file():
            return candidate
    return None


def _diagnostic_run_root(path):
    for parent in (path.parent, *path.parents):
        if any((parent / filename).is_file() for filename in (
                "instrumentation.txt", "host-display-verification.json",
                "collection-status.json")):
            return parent
    return path.parent


def _instrumentation_failure_reason(path):
    if path is None:
        return None
    text = path.read_text(encoding="utf-8-sig")
    failed = (
        "FAILURES!!!" in text
        or "INSTRUMENTATION_STATUS_CODE: -2" in text
        or "Error in " in text
        or "Ten-episode auto-append violations:" in text
    )
    if not failed:
        return None
    lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if "Ten-episode auto-append violations:" in stripped:
            stripped = stripped[stripped.index("Ten-episode auto-append violations:"):]
        if (stripped.startswith("Ten-episode auto-append violations:")
                or stripped.startswith("Error in ")
                or stripped.startswith("There was ")
                or stripped == "FAILURES!!!"):
            if stripped not in lines:
                lines.append(stripped)
    return "\n".join(lines) or "instrumentation failure marker present"


def _positive_non_calibration_counts(series):
    counts = series.get("violationCounts", {})
    if not isinstance(counts, dict):
        raise ValueError("display series violationCounts is malformed")
    positive = {}
    for gate, value in counts.items():
        if not isinstance(gate, str):
            raise ValueError("display series violation count gate is malformed")
        if gate == "calibration":
            continue
        if isinstance(value, bool):
            raise ValueError("display series violation count is malformed")
        if isinstance(value, (int, float)) and value > 0:
            positive[gate] = value

    violations = series.get("violations", [])
    if not isinstance(violations, list):
        raise ValueError("display series violations are malformed")
    non_calibration_violations = []
    calibration_violation_count = 0
    for violation in violations:
        if not isinstance(violation, dict):
            raise ValueError("display series violation is malformed")
        if violation.get("gate") == "calibration":
            calibration_violation_count += 1
        else:
            non_calibration_violations.append(violation)
    return positive, non_calibration_violations, calibration_violation_count, counts


def _display_series_disposition(series):
    positive, non_calibration_violations, calibration_violation_count, counts = \
        _positive_non_calibration_counts(series)
    if positive or non_calibration_violations:
        return "failure", {
            "passed": series.get("passed"),
            "requiresCalibration": series.get("requiresCalibration"),
            "violationCounts": counts,
            "metrics": series.get("metrics"),
        }
    calibration_count = counts.get("calibration", 0)
    if ((isinstance(calibration_count, (int, float)) and not isinstance(calibration_count, bool)
         and calibration_count > 0) or calibration_violation_count > 0):
        return "calibration-only", {
            "passed": series.get("passed"),
            "requiresCalibration": series.get("requiresCalibration"),
            "violationCounts": counts,
        }
    if series.get("passed") is False:
        raise ValueError("display series failed without a bound gate disposition")
    return "pass", None


def _runner_failure_reason(value):
    if isinstance(value, str) and value.strip():
        return value
    if isinstance(value, (list, dict)) and value:
        return value
    if value is None:
        return None
    raise ValueError("single-episode runner failure is malformed")


def _normalize_single_failure(entry):
    regression = entry.get("singleEpisodeRegression")
    if not isinstance(regression, dict):
        raise ValueError("single-lane failure has no strict regression DTO")
    if regression.get("role") != SINGLE_EPISODE_ROLE or \
            regression.get("classification") != SINGLE_EPISODE_CLASSIFICATION:
        raise ValueError("single-lane failure DTO has an invalid role or classification")
    identity = _wfwf_identity(regression, "source")
    if regression.get("kind") != identity["kind"]:
        raise ValueError("single-lane failure kind disagrees with its WFWF identity")
    reason = _runner_failure_reason(entry.get("failure"))
    if reason is None:
        raise ValueError("single-lane failure has no nonblank failure reason")
    provenance = regression.get("provenance")
    if not isinstance(provenance, list) or not provenance:
        raise ValueError("single-lane failure DTO has no provenance")
    for provenance_index, item in enumerate(provenance):
        if not isinstance(item, dict) or not _required_text(item.get("artifact"), "provenance artifact"):
            raise ValueError(f"single-lane provenance {provenance_index} is malformed")
        if not _required_text(item.get("classification"), "provenance classification"):
            raise ValueError(f"single-lane provenance {provenance_index} is malformed")
        if _runner_failure_reason(item.get("reason")) is None:
            raise ValueError(f"single-lane provenance {provenance_index} has no reason")
    return identity, reason, provenance


def _import_runner_single_failures(roots, merged):
    """Re-import failed single-lane attempts without losing their provenance."""
    unresolved = []
    paths = sorted({path.resolve() for root in roots for name in (
        "single-episode-failure.json", "single-episode-failures.json", "*outcomes.json")
                    for path in root.rglob(name) if path.is_file()})
    for path in paths:
        try:
            payload = json.loads(path.read_text(encoding="utf-8-sig"))
            if path.name == "single-episode-failure.json":
                entries = [payload]
            else:
                if not isinstance(payload, list):
                    raise ValueError("single-episode-failures file is not an array")
                entries = payload
            for index, entry in enumerate(entries):
                if not isinstance(entry, dict):
                    raise ValueError(f"single-lane failure {index} is not an object")
                if path.name.endswith("outcomes.json"):
                    if entry.get("role") != SINGLE_EPISODE_ROLE or entry.get("passed") is not False or entry.get("failure") is None:
                        continue
                    entry = {"singleEpisodeRegression": entry, "failure": entry.get("failure")}
                identity, reason, provenance = _normalize_single_failure(entry)
                target = merged.setdefault(_single_episode_key(identity), {
                    **identity,
                    "role": SINGLE_EPISODE_ROLE,
                    "classification": SINGLE_EPISODE_CLASSIFICATION,
                    "provenance": [],
                })
                _merge_provenance(target, {"provenance": provenance})
                _merge_provenance(target, {"provenance": [{
                    "artifact": str(path),
                    "index": index,
                    "classification": "SINGLE_EPISODE_RUN_FAILURE",
                    "reason": reason,
                }]})
        except (OSError, TypeError, ValueError) as error:
            unresolved.append({"artifact": str(path), "reason": str(error),
                               "classification": "UNIDENTIFIABLE_SINGLE_EPISODE_RUN_FAILURE"})
    return unresolved


def _diagnostic_display_failure(path, diagnostic):
    report_path = _find_companion(path, "host-display-verification.json")
    if report_path is None:
        return None, None
    report = json.loads(report_path.read_text(encoding="utf-8-sig"))
    series = report.get("series")
    if not isinstance(series, list):
        raise ValueError("host display report series is malformed")

    collection_key = None
    run_root = _diagnostic_run_root(path)
    collection_files = sorted(run_root.rglob("collection.json"))
    if len(collection_files) == 1:
        collection = json.loads(collection_files[0].read_text(encoding="utf-8-sig"))
        collection_key = _required_text(collection.get("sampleKey"), "collection sampleKey")
    elif len(collection_files) > 1:
        raise ValueError("diagnostic display run has multiple collection identities")

    if collection_key is not None:
        matching = [item for item in series
                    if isinstance(item, dict) and item.get("sampleKey") == collection_key]
        if len(matching) != 1:
            raise ValueError("host display report is not bound to the diagnostic collection")
        selected = matching[0]
    else:
        if len(series) != 1 or not isinstance(series[0], dict):
            raise ValueError("host display report has no unique diagnostic series")
        selected = series[0]

    disposition, reason = _display_series_disposition(selected)
    if disposition == "failure":
        return report_path, reason
    return report_path, None


def _unresolved_wfwf_history(roots):
    """Keep chainless WFWF corpus artifacts visible instead of reporting zero.

    The random-200 WFWF capture predates the JSON outcome contract.  Its
    telemetry can show that work happened, but it cannot establish the exact
    episode identity or an ordered five-episode failed chain.  Treating that
    directory as an empty result would make the prose/history disagree with
    the machine-readable importer output.
    """
    unresolved = []
    required_files = {
        "frame-stats-summary.txt",
        "presentation-evidence.tsv",
        "telemetry-timeline.txt",
    }
    candidates = sorted({path.resolve() for root in roots
                         for path in root.rglob("random-200-wfwf-comic-5-*")})
    for path in candidates:
        if not path.is_dir():
            continue
        files = {child.name for child in path.iterdir() if child.is_file()}
        if not required_files.issubset(files):
            continue
        if any(any(candidate.is_file() for candidate in path.rglob(name)) for name in (
                "summary.json", "outcomes.json", "candidate.json", "collection.json")):
            continue
        unresolved.append({
            "artifact": str(path.resolve()),
            "classification": "UNIDENTIFIABLE_WFWF_HISTORY",
            "reason": (
                "Telemetry/frame artifacts have no exact source, kind, series, episode, "
                "outcome, candidate, or authoritative ordered five-episode chain"
            ),
            "presentFiles": sorted(files),
        })
    return unresolved


def import_single_episode_failures(roots):
    """Preserve identifiable WFWF one-episode failures separately from exact chains."""
    merged = {}
    unresolved = _unresolved_wfwf_history(roots)
    calibration_only = []

    summary_paths = sorted({path.resolve() for root in roots for path in root.rglob("summary.json")})
    for path in summary_paths:
        try:
            payload = json.loads(path.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError, TypeError) as error:
            unresolved.append({"artifact": str(path), "reason": str(error),
                               "classification": "UNREADABLE_WFWF_SUMMARY"})
            continue
        if not isinstance(payload, dict) or payload.get("sourceId") != "wfwf":
            continue
        try:
            reason = _summary_failure_reason(payload.get("violations"))
            if reason is None:
                continue
            identity = _wfwf_identity(payload, "sourceId")
            _add_single_episode_failure(merged, identity, {
                "artifact": str(path),
                "classification": SINGLE_EPISODE_DEVICE_FAILURE,
                "reason": reason,
            })
        except (TypeError, ValueError) as error:
            unresolved.append({"artifact": str(path), "reason": str(error),
                               "classification": "UNIDENTIFIABLE_SINGLE_EPISODE"})

    diagnostic_paths = sorted({path.resolve() for root in roots for path in root.rglob("diagnostic.json")})
    for path in diagnostic_paths:
        try:
            diagnostic = json.loads(path.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError, TypeError) as error:
            unresolved.append({"artifact": str(path), "reason": str(error),
                               "classification": "UNREADABLE_WFWF_DIAGNOSTIC"})
            continue
        if not isinstance(diagnostic, dict) or diagnostic.get("source") != "wfwf":
            continue
        if diagnostic.get("mode") != "DIAGNOSTIC_NO_CORPUS_CREDIT":
            continue
        try:
            identity = _wfwf_identity(diagnostic, "source")
            instrumentation_path = _find_companion(path, "instrumentation.txt")
            instrumentation_reason = _instrumentation_failure_reason(instrumentation_path)
            if instrumentation_reason is not None:
                _add_single_episode_failure(merged, identity, {
                    "artifact": str(instrumentation_path),
                    "diagnosticArtifact": str(path),
                    "classification": SINGLE_EPISODE_DEVICE_FAILURE,
                    "reason": instrumentation_reason,
                })

            report_path, display_reason = _diagnostic_display_failure(path, diagnostic)
            if display_reason is not None:
                _add_single_episode_failure(merged, identity, {
                    "artifact": str(report_path),
                    "diagnosticArtifact": str(path),
                    "classification": SINGLE_EPISODE_DISPLAY_FAILURE,
                    "reason": display_reason,
                })
            elif report_path is not None:
                calibration_only.append({
                    "artifact": str(report_path),
                    "classification": CALIBRATION_ONLY_CLASSIFICATION,
                    "source": identity["source"],
                    "kind": identity["kind"],
                    "seriesKey": identity["seriesKey"],
                    "episodeKey": identity["episodeKey"],
                })
        except (OSError, TypeError, ValueError) as error:
            unresolved.append({"artifact": str(path), "reason": str(error),
                               "classification": "UNIDENTIFIABLE_SINGLE_EPISODE"})

    unresolved.extend(_import_runner_single_failures(roots, merged))
    return sorted(merged.values(), key=lambda item: _single_episode_key(item)), unresolved, calibration_only


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", action="append", type=Path, required=True)
    parser.add_argument("--existing", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--single-episode-output", type=Path, required=True)
    parser.add_argument("--unresolved-output", type=Path, required=True)
    args = parser.parse_args()
    existing = json.loads(args.existing.read_text(encoding="utf-8-sig")) if args.existing and args.existing.exists() else []
    merged, unresolved = import_failures(args.root, existing)
    single_episode, single_unresolved, calibration_only = import_single_episode_failures(args.root)
    unresolved.extend(single_unresolved)
    args.output.write_text(json.dumps(merged, ensure_ascii=False, indent=2), encoding="utf-8")
    args.single_episode_output.write_text(json.dumps(single_episode, ensure_ascii=False, indent=2), encoding="utf-8")
    args.unresolved_output.write_text(json.dumps(unresolved, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"mandatorySamples": len(merged),
                      "singleEpisodeRegressions": len(single_episode),
                      "calibrationOnlyDiagnostics": len(calibration_only),
                      "unresolvedFailures": len(unresolved)}))
    return 1 if unresolved else 0


if __name__ == "__main__":
    raise SystemExit(main())
