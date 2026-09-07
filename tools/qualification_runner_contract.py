"""Fail-closed host checks for one complete corpus attempt.

The instrumentation test records collection progress, while this module checks
that the pulled artifacts still describe the same 4 x 10 x 5 attempt.  It does
not replace the display verifier's identity, coverage, timing, or ownership
checks.  The only nonphysical display result it accepts is the exact,
explicitly bound OBSERVABLE_RENDER_V1 policy profile.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

from import_qualification_failures import normalize_sample, sample_key
from verify_display_trace import (
    OBSERVABLE_ACCEPTANCE_MODE,
    OBSERVABLE_ACCEPTED_RESULT,
    OBSERVABLE_QUALIFICATION_CLAIM,
    PHYSICAL_METRIC_FIELDS,
    PRESENT_EVENT_ORIGIN,
    PRESENT_EVENT_ROLE,
    UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
    is_observable_policy,
    validate_policy,
)


# Compatibility floor only. The importer may recover additional exact chains;
# every supplied chain must still appear in the matching outcome set.
MINIMUM_MANDATORY_REGRESSIONS = 19
CORPUS_GROUPS = {
    ("ntk", "COMIC"),
    ("ntk", "WEBTOON"),
    ("wfwf", "COMIC"),
    ("wfwf", "WEBTOON"),
}
CORPUS_SERIES_PER_GROUP = 10
EPISODES_PER_SERIES = 5
CORPUS_SAMPLE_COUNT = len(CORPUS_GROUPS) * CORPUS_SERIES_PER_GROUP
CORPUS_EPISODE_COUNT = CORPUS_SAMPLE_COUNT * EPISODES_PER_SERIES
SINGLE_EPISODE_ROLE = "MANDATORY_SINGLE_EPISODE_NO_CORPUS_CREDIT"
SINGLE_EPISODE_CLASSIFICATION = "SINGLE_EPISODE_REGRESSION"
EXPECTED_SINGLE_EPISODE_IDENTITIES = {
    ("wfwf", "COMIC", "comic:10001", "1"),
    ("wfwf", "COMIC", "comic:10007", "24"),
    ("wfwf", "COMIC", "comic:10007", "27"),
    ("wfwf", "COMIC", "comic:10007", "28"),
    ("wfwf", "COMIC", "comic:10017", "74"),
}
SINGLE_EPISODE_COUNT = len(EXPECTED_SINGLE_EPISODE_IDENTITIES)


class QualificationContractError(ValueError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise QualificationContractError(message)


def _read(path: Path):
    with path.open(encoding="utf-8-sig") as stream:
        return json.load(stream)


def validate_candidate_metadata(
    attempt: dict,
    run_id: str,
    seed: int,
    installed_apk_sha256: str,
    installed_test_apk_sha256: str,
    qualifier_sha256: str,
    trace_config_sha256: str,
    single_episode_sha256: str,
) -> None:
    """Bind the attempt ledger to the exact app, test APK, collector and trace config."""
    _require(isinstance(attempt, dict), "attempt metadata must be an object")
    _require(attempt.get("runId") == run_id and
             isinstance(attempt.get("seed"), int) and not isinstance(attempt.get("seed"), bool) and
             attempt.get("seed") == seed,
             "attempt metadata does not match this invocation")
    expected = {
        "installedApkSha256": installed_apk_sha256,
        "installedTestApkSha256": installed_test_apk_sha256,
        "qualifierSha256": qualifier_sha256,
        "traceConfigSha256": trace_config_sha256,
        "singleEpisodeRegressionsSha256": single_episode_sha256,
    }
    for field, value in expected.items():
        _require(isinstance(value, str) and re.fullmatch(r"[a-f0-9]{64}", value) is not None,
                 f"expected {field} hash is malformed")
        _require(attempt.get(field) == value, f"attempt {field} does not match the exact candidate")
    _require(isinstance(attempt.get("sourceSnapshotSha256"), str) and
             re.fullmatch(r"[a-f0-9]{64}", attempt["sourceSnapshotSha256"]) is not None,
             "attempt source snapshot hash is missing or malformed")


def validate_policy_binding(policy_path: Path, attempt: dict, display: dict) -> dict:
    """Bind both verifier output and candidate metadata to the frozen policy bytes."""
    _require(policy_path.is_file(), "frozen qualification policy is missing")
    try:
        policy = validate_policy(_read(policy_path))
    except (OSError, json.JSONDecodeError, ValueError) as error:
        raise QualificationContractError(f"frozen qualification policy is invalid: {error}") from error
    digest = hashlib.sha256(policy_path.read_bytes()).hexdigest()
    _require(isinstance(attempt, dict), "attempt metadata must be an object")
    _require(isinstance(attempt.get("policySha256"), str)
             and attempt["policySha256"].lower() == digest,
             "attempt policySha256 does not match the frozen policy bytes")
    _require(isinstance(display, dict), "display report must be an object")
    _require(isinstance(display.get("policySha256"), str)
             and display["policySha256"].lower() == digest,
             "display report policySha256 does not match the frozen policy bytes")
    return policy


def _outcome_sample(outcome: dict):
    return normalize_sample(outcome.get("sample", outcome))


def _identity_set(records, label: str):
    identities = []
    for index, record in enumerate(records):
        try:
            identities.append(sample_key(_outcome_sample(record)))
        except (KeyError, TypeError, ValueError) as error:
            raise QualificationContractError(f"{label}[{index}] has an invalid sample: {error}") from error
    _require(len(identities) == len(set(identities)), f"{label} contains duplicate exact chains")
    return set(identities)


def _single_identity(record: dict, label: str, *, sidecar: bool = False) -> tuple[str, str, str, str]:
    _require(isinstance(record, dict), f"{label} is not an object")
    if sidecar:
        _require(set(record) == {
            "source", "kind", "seriesKey", "episodeKey", "role", "classification", "provenance",
        }, f"{label} has unsupported fields or missing strict DTO fields")
    else:
        _require(not any(field in record for field in ("sample", "title", "episodes", "chain")),
                 f"{label} contains five-episode/sample padding")
    source = record.get("source")
    kind = record.get("kind")
    series = record.get("seriesKey")
    episode = record.get("episodeKey")
    _require(source in ("ntk", "wfwf"), f"{label} has an unsupported source")
    _require(kind in ("COMIC", "WEBTOON"), f"{label} has an unsupported content kind")
    _require(isinstance(series, str) and series.strip(), f"{label} has no exact series identity")
    _require(isinstance(episode, str) and episode.strip(), f"{label} has no exact episode identity")
    _require(record.get("role") == SINGLE_EPISODE_ROLE, f"{label} has an invalid role")
    _require(record.get("classification") == SINGLE_EPISODE_CLASSIFICATION,
             f"{label} has an invalid classification")
    provenance = record.get("provenance")
    _require(isinstance(provenance, list) and provenance, f"{label} has no provenance")
    for provenance_index, item in enumerate(provenance):
        _require(isinstance(item, dict), f"{label} provenance {provenance_index} is not an object")
        _require(isinstance(item.get("artifact"), str) and item["artifact"].strip(),
                 f"{label} provenance {provenance_index} has no artifact")
        _require(isinstance(item.get("classification"), str) and item["classification"].strip(),
                 f"{label} provenance {provenance_index} has no classification")
        reason = item.get("reason")
        has_reason = ((isinstance(reason, str) and bool(reason.strip())) or
                      (isinstance(reason, (list, dict)) and bool(reason)))
        _require(has_reason, f"{label} provenance {provenance_index} has no failure reason")
    return source, kind, series, episode


def validate_single_episode_artifact(path: Path, expected_sha256: str) -> list[dict]:
    """Require the immutable imported sidecar to contain this run's known five identities."""
    _require(path.is_file(), "single-episode regression sidecar is missing")
    _require(isinstance(expected_sha256, str) and re.fullmatch(r"[a-f0-9]{64}", expected_sha256) is not None,
             "expected single-episode sidecar hash is malformed")
    actual_sha256 = hashlib.sha256(path.read_bytes()).hexdigest()
    _require(actual_sha256 == expected_sha256,
             "single-episode sidecar hash does not match the exact candidate")
    records = _read(path)
    _require(isinstance(records, list), "single-episode sidecar must be an array")
    identities = [_single_identity(record, f"single-episode sidecar[{index}]", sidecar=True)
                  for index, record in enumerate(records)]
    _require(identities == sorted(identities),
             "single-episode sidecar order is not the importer’s canonical identity order")
    _require(set(identities) == EXPECTED_SINGLE_EPISODE_IDENTITIES,
             "single-episode sidecar does not contain exactly the five expected identities")
    _require(len(identities) == SINGLE_EPISODE_COUNT and len(set(identities)) == len(identities),
             "single-episode sidecar contains duplicate or missing identities")
    return records


def validate_collection_shape(
    summary: dict,
    outcomes: list,
    prior: list,
    run_id: str,
    seed: int,
    *,
    external_display: bool = True,
    minimum_mandatory: int = MINIMUM_MANDATORY_REGRESSIONS,
    single: list | None = None,
) -> dict:
    """Validate the on-device collection ledger, returning its exact shape."""
    _require(isinstance(summary, dict), "summary must be an object")
    _require(summary.get("schema") == 2, "unsupported corpus summary schema")
    _require(summary.get("runId") == run_id, "summary runId does not match this attempt")
    _require(isinstance(summary.get("seed"), int) and not isinstance(summary.get("seed"), bool) and
             summary.get("seed") == seed, "summary seed does not match this attempt")
    _require(summary.get("requiredEpisodes") == CORPUS_EPISODE_COUNT,
             "summary does not require exactly 200 corpus episodes")
    _require(summary.get("attemptedCompletedEpisodes") == CORPUS_EPISODE_COUNT,
             "summary completed-episode count is not exactly 200")
    _require(summary.get("collectionCompleted") is True,
             "summary does not prove collection completion")
    _require("failure" in summary and summary.get("failure") is None,
             "completed summary contains a failure or omits its failure field")
    _require(summary.get("externalDisplayVerificationRequired") is external_display,
             "summary external-display mode does not match the qualification invocation")
    if external_display:
        _require(summary.get("passed") is False,
                 "external-display collection cannot self-report qualification pass")
    else:
        _require(summary.get("passed") is True,
                 "non-external collection must not self-report an incomplete pass")

    _require(isinstance(outcomes, list), "outcomes must be an array")
    _require(isinstance(prior, list), "prior failures must be an array")
    _require(isinstance(single, list), "single-episode sidecar was not supplied")
    _require(len(prior) >= minimum_mandatory,
             f"only {len(prior)} mandatory regressions were supplied; minimum is {minimum_mandatory}")

    mandatory = [item for item in outcomes
                 if isinstance(item, dict) and item.get("role") == "MANDATORY_REGRESSION_NO_CORPUS_CREDIT"]
    corpus = [item for item in outcomes
              if isinstance(item, dict) and item.get("role") == "CORPUS"]
    single_outcomes = [item for item in outcomes
                       if isinstance(item, dict) and item.get("role") == SINGLE_EPISODE_ROLE]
    _require(len(mandatory) + len(corpus) + len(single_outcomes) == len(outcomes),
             "outcomes contains an unknown role or malformed record")
    _require(len(mandatory) == len(prior),
             "mandatory outcome count does not match the supplied regression set")
    _require(len(corpus) == CORPUS_SAMPLE_COUNT,
             "outcomes does not contain exactly 40 corpus samples")
    _require(len(single) == SINGLE_EPISODE_COUNT,
             "single-episode sidecar does not contain exactly five expected regressions")
    _require(len(single_outcomes) == len(single),
             "single-episode outcome count does not match the imported sidecar")

    for index, item in enumerate(mandatory):
        _require(item.get("sampleKey") == f"regression-{index}-{run_id}",
                 f"mandatory outcome {index} is missing its exact runner sample key")
    for index, item in enumerate(corpus, 1):
        _require(item.get("sampleKey") == f"corpus-{run_id}-{index}",
                 f"corpus outcome {index} is missing its exact runner sample key")
    for index, item in enumerate(single_outcomes, 1):
        _require(item.get("sampleKey") == f"single-{run_id}-{index}",
                 f"single-episode outcome {index} is missing its exact runner sample key")
    for index, item in enumerate(prior):
        _require(isinstance(item, dict) and item.get("role", "MANDATORY_REGRESSION_NO_CORPUS_CREDIT") ==
                 "MANDATORY_REGRESSION_NO_CORPUS_CREDIT",
                 f"prior regression {index} has an invalid role")

    prior_ids = _identity_set(prior, "prior failures")
    mandatory_ids = _identity_set(mandatory, "mandatory outcomes")
    _require(prior_ids == mandatory_ids,
             "mandatory outcome identities differ from the exact supplied regression chains")
    for index, item in enumerate(mandatory):
        _require(item.get("passed") is True, f"mandatory regression {index} is not recorded as passed")
        _require("failure" in item and item.get("failure") is None,
                 f"mandatory regression {index} contains a failure or omits its failure field")

    sidecar_ids = [_single_identity(item, f"single-episode sidecar[{index}]", sidecar=True)
                   for index, item in enumerate(single)]
    _require(sidecar_ids == sorted(sidecar_ids) and set(sidecar_ids) == EXPECTED_SINGLE_EPISODE_IDENTITIES,
             "single-episode sidecar identities are not the exact expected set")
    outcome_ids = [_single_identity(item, f"single-episode outcome[{index}]")
                   for index, item in enumerate(single_outcomes)]
    _require(sidecar_ids == outcome_ids,
             "single-episode outcome identities differ from the immutable imported sidecar")
    for index, (expected_record, item) in enumerate(zip(single, single_outcomes)):
        _require(item.get("provenance") == expected_record.get("provenance"),
                 f"single-episode outcome {index} lost or substituted provenance")
        _require(item.get("passed") is True,
                 f"single-episode regression {index} is not recorded as passed")
        _require(item.get("collectionCompleted") is True,
                 f"single-episode regression {index} is not individually collection-complete")
        _require("failure" in item and item.get("failure") is None,
                 f"single-episode regression {index} contains a failure or omits its failure field")

    corpus_ids = _identity_set(corpus, "corpus outcomes")
    grouped: dict[tuple[str, str], list[tuple]] = {}
    for index, item in enumerate(corpus):
        sample = _outcome_sample(item)
        identity = sample_key(sample)
        grouped.setdefault((sample["source"], sample["kind"]), []).append(identity)
        _require(item.get("collectionCompleted") is True,
                 f"corpus sample {index} is not individually collection-complete")
        _require("failure" in item and item.get("failure") is None,
                 f"corpus sample {index} contains a failure")
        expected_pass = False if external_display else True
        _require(item.get("passed") is expected_pass,
                 f"corpus sample {index} has an invalid pending/pass state")

    _require(set(grouped) == CORPUS_GROUPS,
             "corpus does not cover exactly NTK/WFWF x COMIC/WEBTOON")
    for group, identities in grouped.items():
        _require(len(identities) == CORPUS_SERIES_PER_GROUP,
                 f"{group} does not contain exactly 10 works")
        series = {identity[2] for identity in identities}
        _require(len(series) == CORPUS_SERIES_PER_GROUP,
                 f"{group} repeats a work instead of selecting 10 distinct works")
    _require(len(corpus_ids) == CORPUS_SAMPLE_COUNT and
             sum(len(_outcome_sample(item)["episodes"]) for item in corpus) == CORPUS_EPISODE_COUNT,
             "corpus does not contain exactly 200 episode identities")
    _require(summary.get("consecutivePassed") == (0 if external_display else CORPUS_EPISODE_COUNT),
             "summary consecutive-pass count is inconsistent with the display mode")

    return {"mandatoryCount": len(mandatory), "corpusCount": len(corpus),
            "singleEpisodeCount": len(single_outcomes),
            "expectedSampleCount": len(mandatory) + len(single_outcomes) + len(corpus),
            "corpusIdentities": corpus_ids}


def expected_sample_keys(run_id: str, mandatory_count: int,
                         single_count: int = SINGLE_EPISODE_COUNT) -> set[str]:
    return ({f"regression-{index}-{run_id}" for index in range(mandatory_count)} |
            {f"single-{run_id}-{index}" for index in range(1, single_count + 1)} |
            {f"corpus-{run_id}-{index}" for index in range(1, CORPUS_SAMPLE_COUNT + 1)})


def validate_evidence_identity(evidence_root: Path, outcomes: list, run_id: str,
                               mandatory_count: int,
                               single_count: int = SINGLE_EPISODE_COUNT) -> None:
    """Bind every pulled sample/collection directory to its exact outcome identity."""
    _require(evidence_root.is_dir(), "pulled evidence root is missing")
    expected = {}
    expected_outcomes = {}
    for index, outcome in enumerate(outcomes):
        _require(isinstance(outcome, dict), f"outcome {index} is not an object")
        key = outcome.get("sampleKey")
        _require(isinstance(key, str) and key, f"outcome {index} lacks sampleKey")
        _require(key not in expected, f"outcomes contains duplicate sampleKey {key}")
        if outcome.get("role") == SINGLE_EPISODE_ROLE:
            expected[key] = _single_identity(outcome, f"single-episode outcome {index}")
            expected_outcomes[key] = outcome
        else:
            expected[key] = sample_key(_outcome_sample(outcome))
    _require(set(expected) == expected_sample_keys(run_id, mandatory_count, single_count),
             "outcome sample keys do not describe this complete attempt")

    wrapper_paths = sorted(evidence_root.rglob("sample.json"))
    wrapper_locations = {path.resolve() for path in wrapper_paths}
    wrappers = {}
    for path in wrapper_paths:
        document = _read(path)
        key = document.get("sampleKey") if isinstance(document, dict) else None
        _require(isinstance(key, str) and key, f"sample wrapper {path} lacks sampleKey")
        _require(path.parent.name == key, f"sample wrapper {path} is stored under a different sample key")
        _require(key not in wrappers, f"pulled evidence contains duplicate sample wrapper {key}")
        if key.startswith(f"single-{run_id}-"):
            _require(document.get("role") == SINGLE_EPISODE_ROLE,
                     f"single wrapper {key} has an invalid role")
            regression = document.get("singleEpisodeRegression")
            identity = _single_identity(regression, f"single wrapper {key}", sidecar=True)
            expected_outcome = expected_outcomes.get(key)
            _require(isinstance(expected_outcome, dict) and
                     regression.get("provenance") == expected_outcome.get("provenance"),
                     f"single wrapper {key} lost or substituted provenance")
            series = document.get("resolvedSeries")
            episode = document.get("resolvedEpisode")
            _require(isinstance(series, dict) and isinstance(episode, dict),
                     f"single wrapper {key} lacks live resolved metadata")
            _require(series.get("source") == identity[0] and series.get("kind") == identity[1] and
                     series.get("seriesKey") == identity[2] and
                     isinstance(series.get("title"), str) and series["title"].strip(),
                     f"single wrapper {key} has substituted or missing live series metadata")
            _require(episode.get("episodeKey") == identity[3] and
                     isinstance(episode.get("title"), str) and episode["title"].strip(),
                     f"single wrapper {key} has substituted or missing live episode metadata")
            wrappers[key] = identity
        else:
            wrappers[key] = sample_key(normalize_sample(document.get("sample")))
    _require(set(wrappers) == set(expected),
             "pulled evidence sample wrappers do not cover the complete attempt")
    for key, identity in expected.items():
        _require(wrappers[key] == identity,
                 f"pulled evidence sample {key} was substituted for the recorded outcome chain")

    collections = {}
    for path in sorted(evidence_root.rglob("collection.json")):
        document = _read(path)
        key = document.get("sampleKey") if isinstance(document, dict) else None
        _require(isinstance(key, str) and key, f"collection {path} lacks sampleKey")
        _require(path.parent.parent.name == key,
                 f"collection {path} is stored under a different sample key")
        _require(key not in collections, f"pulled evidence contains duplicate collection {key}")
        collections[key] = path
        _require(key in expected, f"collection {key} is not part of this attempt")
        if key.startswith(f"single-{run_id}-"):
            _require(document.get("requiredEpisodes") == 1,
                     f"single collection {key} does not require exactly one episode")
            _require(document.get("mode") == "QUALIFICATION",
                     f"single collection {key} was run in diagnostic mode")
        _require((path.parent.parent / "sample.json").resolve() in wrapper_locations,
                 f"collection {key} is detached from its sample wrapper")
    _require(set(collections) == set(expected),
             "pulled evidence collections do not cover the complete attempt")


def sample_specific_display_failures(display: dict) -> list[dict]:
    """Return only reports with a sample-specific failure, not calibration-only reports."""
    _require(isinstance(display, dict), "display report must be an object")
    reports = display.get("series")
    _require(isinstance(reports, list), "display report series must be an array")
    result = []
    for index, report in enumerate(reports):
        _require(isinstance(report, dict), f"display series {index} is not an object")
        counts = report.get("violationCounts")
        _require(isinstance(counts, dict), f"display series {index} lacks violation counts")
        _require(all(isinstance(key, str) and isinstance(value, int) and not isinstance(value, bool) and value >= 0
                     for key, value in counts.items()),
                 f"display series {index} has malformed violation counts")
        if any(key != "calibration" and isinstance(value, int) and not isinstance(value, bool) and value > 0
               for key, value in counts.items()):
            result.append(report)
    return result


def validate_display_shape(display: dict, run_id: str, mandatory_count: int,
                           single_count: int = SINGLE_EPISODE_COUNT) -> dict:
    """Ensure the display verifier saw every expected collection, even on failure."""
    _require(isinstance(display, dict), "display report must be an object")
    reports = display.get("series")
    _require(isinstance(reports, list), "display report series must be an array")
    expected = expected_sample_keys(run_id, mandatory_count, single_count)
    actual = []
    for index, report in enumerate(reports):
        _require(isinstance(report, dict), f"display series {index} is not an object")
        key = report.get("sampleKey")
        _require(isinstance(key, str) and key, f"display series {index} lacks sampleKey")
        counts = report.get("violationCounts")
        _require(isinstance(counts, dict), f"display series {index} lacks violation counts")
        _require(all(isinstance(name, str) and isinstance(value, int) and not isinstance(value, bool) and value >= 0
                     for name, value in counts.items()),
                 f"display series {index} has malformed violation counts")
        actual.append(key)
    _require(len(actual) == len(set(actual)), "display verifier returned duplicate sampleKey values")
    _require(set(actual) == expected,
             "display verifier evidence directories do not match the complete attempt")
    return {"expectedSampleKeys": expected, "reports": reports}


def _require_unavailable_physical_metrics(record: dict, label: str) -> None:
    metrics = record.get("physicalMetrics")
    _require(isinstance(metrics, dict) and set(metrics) == set(PHYSICAL_METRIC_FIELDS),
             f"{label} lacks the complete unavailable physical metric disclosure")
    _require(all(metrics[field] is None for field in PHYSICAL_METRIC_FIELDS),
             f"{label} claims a physical presentation metric")


def _require_observable_disclosure(record: dict, label: str) -> None:
    uncertainty = record.get("measurementUncertainty")
    _require(isinstance(uncertainty, dict),
             f"{label} lacks measurement uncertainty disclosure")
    _require(uncertainty.get("presentEventOrigin") == PRESENT_EVENT_ORIGIN,
             f"{label} has an ambiguous present-event origin")
    _require(uncertainty.get("presentEventRole") == PRESENT_EVENT_ROLE,
             f"{label} does not label present events as composition proxies")
    _require(uncertainty.get("physicalPresentationTiming") == UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
             f"{label} does not disclose unavailable physical presentation timing")


def _validate_observable_display_pass(display: dict, reports: list[dict], policy: dict) -> None:
    _require(is_observable_policy(policy),
             "observable display acceptance requires the exact authorized policy profile")
    _require(display.get("acceptanceMode") == OBSERVABLE_ACCEPTANCE_MODE,
             "display report acceptanceMode is not the authorized observable profile")
    _require(display.get("physicalPresentationTiming") == UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
             "display report physicalPresentationTiming is not the authorized disclosure")
    _require(display.get("result") == OBSERVABLE_ACCEPTED_RESULT,
             "display report did not return the authorized observable result")
    _require(display.get("passed") is True and display.get("observableEvidenceComplete") is True,
             "display verifier did not grant complete observable evidence")
    _require(display.get("displayEvidenceComplete") is False,
             "observable result claims complete physical display evidence")
    _require(display.get("exactPhysicalPresentationTimeVerified") is False,
             "observable result claims an exact physical presentation timestamp")
    _require(display.get("physicalTimingComplete") is False,
             "observable result claims complete physical timing")
    _require(display.get("requiresCalibration") is True,
             "observable result did not retain the physical calibration requirement")
    _require(display.get("qualificationClaim") == OBSERVABLE_QUALIFICATION_CLAIM,
             "display verifier did not state the observable physical-timing limitation")
    _require_observable_disclosure(display, "display report")
    _require_unavailable_physical_metrics(display, "display report")
    for field in ("displayCorrelationComplete", "coverageComplete", "timingComplete",
                  "noAutoJumpEvidenceComplete"):
        _require(display.get(field) is True, f"display verifier omitted passing {field}")
    global_counts = display.get("violationCounts")
    _require(isinstance(global_counts, dict) and not global_counts,
             "display verifier retained global violations")
    _require(isinstance(display.get("violations"), list) and not display["violations"],
             "display verifier retained global violation details")
    for report in reports:
        label = f"display series {report.get('sampleKey')}"
        _require(report.get("passed") is True and report.get("observableEvidenceComplete") is True,
                 f"{label} did not pass observable evidence")
        _require(report.get("acceptanceMode") == OBSERVABLE_ACCEPTANCE_MODE,
                 f"{label} has a mismatched acceptanceMode")
        _require(report.get("physicalPresentationTiming") == UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
                 f"{label} has a mismatched physicalPresentationTiming disclosure")
        _require(report.get("result") == OBSERVABLE_ACCEPTED_RESULT,
                 f"{label} has a mismatched observable result")
        _require(report.get("displayEvidenceComplete") is False and
                 report.get("exactPhysicalPresentationTimeVerified") is False,
                 f"{label} claims physical display evidence")
        _require(report.get("physicalTimingComplete") is False and
                  report.get("requiresCalibration") is True,
                  f"{label} lost the physical calibration limitation")
        _require_observable_disclosure(report, label)
        _require_unavailable_physical_metrics(report, label)
        _require(isinstance(report.get("violations"), list) and not report["violations"],
                 f"{label} retained violation details")
        for field in ("displayCorrelationComplete", "coverageComplete", "timingComplete",
                      "noAutoJumpEvidenceComplete"):
            _require(report.get(field) is True, f"{label} omitted passing {field}")
        metrics = report.get("metrics")
        _require(isinstance(metrics, dict) and metrics.get("expectedPages", 0) > 0 and
                 metrics.get("fullyCoveredPages") == metrics.get("expectedPages"),
                 f"{label} lacks complete source-row coverage")
        _require(isinstance(report.get("violationCounts"), dict) and not report["violationCounts"],
                 f"{label} retained violations")


def validate_display_pass(display: dict, run_id: str, mandatory_count: int,
                          single_count: int = SINGLE_EPISODE_COUNT,
                          policy: dict | None = None) -> None:
    """Require strict physical proof or the exact authorized observable profile."""
    validated = validate_display_shape(display, run_id, mandatory_count, single_count)
    if policy is not None:
        try:
            policy = validate_policy(policy)
        except ValueError as error:
            raise QualificationContractError(str(error)) from error
    if policy is not None and is_observable_policy(policy):
        _validate_observable_display_pass(display, validated["reports"], policy)
        return
    _require(display.get("passed") is True, "display verifier did not grant pass")
    for field in ("displayEvidenceComplete", "displayCorrelationComplete", "coverageComplete",
                  "timingComplete", "noAutoJumpEvidenceComplete"):
        _require(display.get(field) is True, f"display verifier omitted passing {field}")
    _require(display.get("requiresCalibration") is False,
             "display verifier still requires independent calibration")
    global_counts = display.get("violationCounts")
    _require(isinstance(global_counts, dict) and not global_counts,
             "display verifier retained global violations")
    for report in validated["reports"]:
        _require(report.get("passed") is True, f"display series {report.get('sampleKey')} did not pass")
        for field in ("displayCorrelationComplete", "coverageComplete", "timingComplete",
                      "noAutoJumpEvidenceComplete"):
            _require(report.get(field) is True,
                     f"display series {report.get('sampleKey')} omitted passing {field}")
        metrics = report.get("metrics")
        _require(isinstance(metrics, dict) and metrics.get("expectedPages", 0) > 0 and
                 metrics.get("fullyCoveredPages") == metrics.get("expectedPages"),
                 f"display series {report.get('sampleKey')} lacks complete source-row coverage")
        _require(isinstance(report.get("violationCounts"), dict) and not report["violationCounts"],
                 f"display series {report.get('sampleKey')} retained violations")


def _main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("shape", "pass"))
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--outcomes", type=Path, required=True)
    parser.add_argument("--prior", type=Path, required=True)
    parser.add_argument("--single", type=Path, required=True)
    parser.add_argument("--single-episode-sha256", required=True)
    parser.add_argument("--display", type=Path, required=True)
    parser.add_argument("--policy", type=Path,
                        help="Frozen policy whose bytes and acceptance profile are bound to the attempt")
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--attempt", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--installed-apk-sha256", required=True)
    parser.add_argument("--installed-test-apk-sha256", required=True)
    parser.add_argument("--qualifier-sha256", required=True)
    parser.add_argument("--trace-config-sha256", required=True)
    args = parser.parse_args(argv)
    try:
        validate_candidate_metadata(_read(args.attempt), args.run_id, args.seed,
                                    args.installed_apk_sha256, args.installed_test_apk_sha256,
                                    args.qualifier_sha256, args.trace_config_sha256,
                                    args.single_episode_sha256)
        attempt = _read(args.attempt)
        display = _read(args.display)
        policy = validate_policy_binding(args.policy, attempt, display) if args.policy else None
        outcomes = _read(args.outcomes)
        single = validate_single_episode_artifact(args.single, args.single_episode_sha256)
        shape = validate_collection_shape(_read(args.summary), outcomes, _read(args.prior),
                                          args.run_id, args.seed, single=single)
        validate_evidence_identity(args.evidence_root, outcomes, args.run_id, shape["mandatoryCount"],
                                   shape["singleEpisodeCount"])
        validate_display_shape(display, args.run_id, shape["mandatoryCount"],
                               shape["singleEpisodeCount"])
        if args.command == "pass":
            validate_display_pass(display, args.run_id, shape["mandatoryCount"],
                                  shape["singleEpisodeCount"], policy=policy)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"qualification contract failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps({"passed": True, **shape}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
