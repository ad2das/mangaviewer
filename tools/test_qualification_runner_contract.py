import json
import hashlib
import tempfile
import unittest
from pathlib import Path

from qualification_runner_contract import (
    CORPUS_GROUPS,
    OBSERVABLE_ACCEPTANCE_MODE,
    OBSERVABLE_ACCEPTED_RESULT,
    OBSERVABLE_QUALIFICATION_CLAIM,
    PHYSICAL_METRIC_FIELDS,
    PRESENT_EVENT_ORIGIN,
    PRESENT_EVENT_ROLE,
    QualificationContractError,
    SINGLE_EPISODE_CLASSIFICATION,
    SINGLE_EPISODE_ROLE,
    UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
    validate_single_episode_artifact,
    expected_sample_keys,
    sample_specific_display_failures,
    validate_candidate_metadata,
    validate_collection_shape,
    validate_display_pass,
    validate_display_shape,
    validate_evidence_identity,
    validate_policy_binding,
)


RUN_ID = "run-fixture"
SEED = 123
OBSERVABLE_POLICY = {
    "exceptions": [],
    "acceptanceMode": OBSERVABLE_ACCEPTANCE_MODE,
    "physicalPresentationTiming": UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
}


def sample(source, kind, series, offset):
    return {
        "source": source,
        "kind": kind,
        "seriesKey": f"{series}-{offset}",
        "title": f"work-{series}-{offset}",
        "episodes": [{"key": f"{series}-{offset}/episode-{index}", "title": f"episode-{index}"}
                     for index in range(5)],
    }


def outcome(sample_value, role, passed, **extra):
    return {**sample_value, "role": role, "passed": passed, **extra}


class QualificationRunnerContractTest(unittest.TestCase):
    def setUp(self):
        self.prior = [
            {"role": "MANDATORY_REGRESSION_NO_CORPUS_CREDIT",
             "sample": sample("ntk", "COMIC", "prior", index), "provenance": []}
            for index in range(19)
        ]
        corpus_descriptors = [
            (source, kind, index)
            for source, kind in sorted(CORPUS_GROUPS)
            for index in range(10)
        ]
        self.corpus = [
            outcome(sample(source, kind, f"group-{source}-{kind}", index), "CORPUS", False,
                    sampleKey=f"corpus-{RUN_ID}-{ordinal}", collectionCompleted=True, failure=None)
            for ordinal, (source, kind, index) in enumerate(corpus_descriptors, 1)
        ]
        self.single = [
            {
                "source": "wfwf",
                "kind": "COMIC",
                "seriesKey": series,
                "episodeKey": episode,
                "role": SINGLE_EPISODE_ROLE,
                "classification": SINGLE_EPISODE_CLASSIFICATION,
                "provenance": [{"artifact": f"fixture-{series}-{episode}",
                                "classification": "SINGLE_EPISODE_DEVICE_FAILURE",
                                "reason": "fixture failure"}],
            }
            for series, episode in (("comic:10001", "1"), ("comic:10007", "24"),
                                    ("comic:10007", "27"), ("comic:10007", "28"),
                                    ("comic:10017", "74"))
        ]
        self.single_outcomes = [
            {**item, "sampleKey": f"single-{RUN_ID}-{index + 1}", "passed": True,
             "collectionCompleted": True, "failure": None}
            for index, item in enumerate(self.single)
        ]
        self.outcomes = [
            outcome(item["sample"], item["role"], True,
                    sampleKey=f"regression-{index}-{RUN_ID}", failure=None)
            for index, item in enumerate(self.prior)
        ] + self.single_outcomes + self.corpus
        self.summary = {
            "schema": 2,
            "runId": RUN_ID,
            "seed": SEED,
            "requiredEpisodes": 200,
            "attemptedCompletedEpisodes": 200,
            "collectionCompleted": True,
            "externalDisplayVerificationRequired": True,
            "passed": False,
            "consecutivePassed": 0,
            "failure": None,
        }

    def observable_display(self):
        def report(key):
            return {
                "sampleKey": key,
                "passed": True,
                "displayEvidenceComplete": False,
                "observableEvidenceComplete": True,
                "result": OBSERVABLE_ACCEPTED_RESULT,
                "acceptanceMode": OBSERVABLE_ACCEPTANCE_MODE,
                "physicalPresentationTiming": UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
                "exactPhysicalPresentationTimeVerified": False,
                "physicalTimingComplete": False,
                "requiresCalibration": True,
                "measurementUncertainty": {
                    "presentEventOrigin": PRESENT_EVENT_ORIGIN,
                    "presentEventRole": PRESENT_EVENT_ROLE,
                    "physicalPresentationTiming": UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
                },
                "displayCorrelationComplete": True,
                "coverageComplete": True,
                "timingComplete": True,
                "noAutoJumpEvidenceComplete": True,
                "physicalMetrics": {field: None for field in PHYSICAL_METRIC_FIELDS},
                "metrics": {"expectedPages": 1, "fullyCoveredPages": 1},
                "violations": [],
                "violationCounts": {},
            }
        return {
            "passed": True,
            "displayEvidenceComplete": False,
            "observableEvidenceComplete": True,
            "result": OBSERVABLE_ACCEPTED_RESULT,
            "acceptanceMode": OBSERVABLE_ACCEPTANCE_MODE,
            "physicalPresentationTiming": UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
            "exactPhysicalPresentationTimeVerified": False,
            "physicalTimingComplete": False,
            "requiresCalibration": True,
            "measurementUncertainty": {
                "presentEventOrigin": PRESENT_EVENT_ORIGIN,
                "presentEventRole": PRESENT_EVENT_ROLE,
                "physicalPresentationTiming": UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
            },
            "displayCorrelationComplete": True,
            "coverageComplete": True,
            "timingComplete": True,
            "noAutoJumpEvidenceComplete": True,
            "physicalMetrics": {field: None for field in PHYSICAL_METRIC_FIELDS},
            "qualificationClaim": OBSERVABLE_QUALIFICATION_CLAIM,
            "violations": [],
            "violationCounts": {},
            "series": [report(key) for key in sorted(expected_sample_keys(RUN_ID, 19, 5))],
        }

    def test_candidate_metadata_rejects_a_mismatched_test_artifact_hash(self):
        hashes = {
            "installedApkSha256": "a" * 64,
            "installedTestApkSha256": "b" * 64,
            "qualifierSha256": "c" * 64,
            "traceConfigSha256": "d" * 64,
            "singleEpisodeRegressionsSha256": "f" * 64,
        }
        attempt = {"runId": RUN_ID, "seed": SEED, "sourceSnapshotSha256": "e" * 64, **hashes}

        validate_candidate_metadata(attempt, RUN_ID, SEED, **{
            "installed_apk_sha256": hashes["installedApkSha256"],
            "installed_test_apk_sha256": hashes["installedTestApkSha256"],
            "qualifier_sha256": hashes["qualifierSha256"],
            "trace_config_sha256": hashes["traceConfigSha256"],
            "single_episode_sha256": hashes["singleEpisodeRegressionsSha256"],
        })
        with self.assertRaises(QualificationContractError):
            validate_candidate_metadata(attempt, RUN_ID, SEED, hashes["installedApkSha256"],
                                        "f" * 64, hashes["qualifierSha256"], hashes["traceConfigSha256"],
                                        hashes["singleEpisodeRegressionsSha256"])

    def test_fresh_four_group_shape_requires_40_distinct_episodes_samples(self):
        shape = validate_collection_shape(self.summary, self.outcomes, self.prior, RUN_ID, SEED,
                                          single=self.single)
        self.assertEqual(19, shape["mandatoryCount"])
        self.assertEqual(5, shape["singleEpisodeCount"])
        self.assertEqual(40, shape["corpusCount"])

    def test_sample_wrapper_substitution_cannot_pass_exact_identity_check(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for item in self.outcomes:
                sample_key_value = item["sampleKey"]
                sample_directory = root / sample_key_value
                capture_directory = sample_directory / f"{sample_key_value}-capture"
                capture_directory.mkdir(parents=True)
                if item.get("role") == SINGLE_EPISODE_ROLE:
                    regression = {key: item[key] for key in (
                        "source", "kind", "seriesKey", "episodeKey", "role", "classification", "provenance")}
                    wrapper = {
                        "sampleKey": sample_key_value,
                        "role": SINGLE_EPISODE_ROLE,
                        "singleEpisodeRegression": regression,
                        "resolvedSeries": {"source": item["source"], "kind": item["kind"],
                                            "seriesKey": item["seriesKey"], "title": "live work"},
                        "resolvedEpisode": {"episodeKey": item["episodeKey"], "title": "live episode"},
                    }
                else:
                    wrapper = {"sampleKey": sample_key_value, "sample": item}
                (sample_directory / "sample.json").write_text(json.dumps(wrapper), encoding="utf-8")
                collection = {"sampleKey": sample_key_value}
                if item.get("role") == SINGLE_EPISODE_ROLE:
                    collection.update({"requiredEpisodes": 1, "mode": "QUALIFICATION"})
                (capture_directory / "collection.json").write_text(json.dumps(collection), encoding="utf-8")
            validate_evidence_identity(root, self.outcomes, RUN_ID, 19, 5)
            wrapper = root / self.outcomes[19]["sampleKey"] / "sample.json"
            wrapper.write_text(json.dumps({
                "sampleKey": self.outcomes[19]["sampleKey"],
                "role": SINGLE_EPISODE_ROLE,
                "singleEpisodeRegression": self.single[1],
                "resolvedSeries": {"source": "wfwf", "kind": "COMIC",
                                    "seriesKey": "comic:10007", "title": "live work"},
                "resolvedEpisode": {"episodeKey": "24", "title": "live episode"},
            }), encoding="utf-8")
            with self.assertRaises(QualificationContractError):
                validate_evidence_identity(root, self.outcomes, RUN_ID, 19, 5)

    def test_repeated_work_in_a_group_cannot_fill_ten_work_quota(self):
        repeated = list(self.corpus)
        repeated[1] = {**repeated[0]}
        with self.assertRaises(QualificationContractError):
            validate_collection_shape(self.summary, self.outcomes[:19] + self.single_outcomes + repeated,
                                      self.prior, RUN_ID, SEED, single=self.single)

    def test_missing_single_sidecar_cannot_fill_the_fresh_attempt(self):
        with self.assertRaises(QualificationContractError):
            validate_collection_shape(self.summary, self.outcomes, self.prior, RUN_ID, SEED, single=[])

    def test_substituted_single_identity_cannot_pass_the_immutable_lane(self):
        substituted = [dict(item) for item in self.single]
        substituted[0]["episodeKey"] = "not-the-recorded-episode"
        with self.assertRaises(QualificationContractError):
            validate_collection_shape(self.summary, self.outcomes, self.prior, RUN_ID, SEED,
                                      single=substituted)

    def test_single_episode_failure_blocks_fresh_corpus_credit(self):
        failed = [dict(item) for item in self.single_outcomes]
        failed[0]["passed"] = False
        failed[0]["failure"] = "single lane failed"
        with self.assertRaises(QualificationContractError):
            validate_collection_shape(self.summary,
                                      self.outcomes[:19] + failed + self.corpus,
                                      self.prior, RUN_ID, SEED, single=self.single)

    def test_single_provenance_loss_cannot_pass_identity_only_checks(self):
        substituted = [dict(item) for item in self.single_outcomes]
        substituted[0]["provenance"] = [{"artifact": "replacement", "classification":
                                          "SINGLE_EPISODE_DEVICE_FAILURE", "reason": "replacement"}]
        with self.assertRaises(QualificationContractError):
            validate_collection_shape(self.summary,
                                      self.outcomes[:19] + substituted + self.corpus,
                                      self.prior, RUN_ID, SEED, single=self.single)

    def test_fake_five_episode_padding_is_rejected_for_single_sidecar(self):
        padded = [dict(item) for item in self.single]
        padded[0]["episodes"] = [{"key": "invented", "title": "invented"}] * 5
        with self.assertRaises(QualificationContractError):
            validate_collection_shape(self.summary, self.outcomes, self.prior, RUN_ID, SEED,
                                      single=padded)

    def test_single_sidecar_hash_is_bound_to_the_exact_importer_output(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "single.json"
            path.write_text(json.dumps(self.single), encoding="utf-8")
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            validate_single_episode_artifact(path, digest)
            with self.assertRaises(QualificationContractError):
                validate_single_episode_artifact(path, "0" * 64)

    def test_display_shape_rejects_missing_or_substituted_series(self):
        keys = sorted(expected_sample_keys(RUN_ID, 19, 5))
        display = {"series": [{"sampleKey": key, "violationCounts": {}} for key in keys]}
        validate_display_shape(display, RUN_ID, 19)
        display["series"][-1]["sampleKey"] = "substituted"
        with self.assertRaises(QualificationContractError):
            validate_display_shape(display, RUN_ID, 19)

    def test_calibration_only_display_failure_does_not_create_chain_regressions(self):
        reports = [
            {"sampleKey": "calibration-only", "violationCounts": {"calibration": 1}},
            {"sampleKey": "sample-failed", "violationCounts": {"calibration": 1, "coverage": 1}},
        ]
        self.assertEqual([reports[1]], sample_specific_display_failures({"series": reports}))

    def test_display_pass_requires_every_series_source_row_and_timing_gate(self):
        keys = sorted(expected_sample_keys(RUN_ID, 19, 5))
        report = lambda key: {
            "sampleKey": key,
            "passed": True,
            "displayCorrelationComplete": True,
            "coverageComplete": True,
            "timingComplete": True,
            "noAutoJumpEvidenceComplete": True,
            "metrics": {"expectedPages": 1, "fullyCoveredPages": 1},
            "violationCounts": {},
        }
        display = {
            "passed": True,
            "displayEvidenceComplete": True,
            "displayCorrelationComplete": True,
            "coverageComplete": True,
            "timingComplete": True,
            "noAutoJumpEvidenceComplete": True,
            "requiresCalibration": False,
            "violationCounts": {},
            "series": [report(key) for key in keys],
        }
        validate_display_pass(display, RUN_ID, 19)
        display["series"][0]["metrics"]["fullyCoveredPages"] = 0
        with self.assertRaises(QualificationContractError):
            validate_display_pass(display, RUN_ID, 19)

    def test_strict_default_rejects_unavailable_physical_display_fixture(self):
        with self.assertRaises(QualificationContractError):
            validate_display_pass(self.observable_display(), RUN_ID, 19)

    def test_observable_profile_accepts_only_complete_nonphysical_evidence(self):
        validate_display_pass(self.observable_display(), RUN_ID, 19, policy=OBSERVABLE_POLICY)

    def test_observable_profile_rejects_missing_page_or_row(self):
        display = self.observable_display()
        display["series"][0]["coverageComplete"] = False
        with self.assertRaises(QualificationContractError):
            validate_display_pass(display, RUN_ID, 19, policy=OBSERVABLE_POLICY)
        display = self.observable_display()
        display["series"][0]["metrics"]["fullyCoveredPages"] = 0
        with self.assertRaises(QualificationContractError):
            validate_display_pass(display, RUN_ID, 19, policy=OBSERVABLE_POLICY)

    def test_observable_profile_rejects_identity_or_stall_failure(self):
        for field in ("displayCorrelationComplete", "timingComplete", "noAutoJumpEvidenceComplete"):
            display = self.observable_display()
            display["series"][0][field] = False
            with self.assertRaises(QualificationContractError):
                validate_display_pass(display, RUN_ID, 19, policy=OBSERVABLE_POLICY)

    def test_observable_profile_requires_explicit_proxy_and_failure_disclosure(self):
        cases = []
        missing_claim = self.observable_display()
        del missing_claim["qualificationClaim"]
        cases.append(missing_claim)
        wrong_origin = self.observable_display()
        wrong_origin["measurementUncertainty"]["presentEventOrigin"] = "SYNTHETIC_PRESENT"
        cases.append(wrong_origin)
        wrong_role = self.observable_display()
        wrong_role["series"][0]["measurementUncertainty"]["presentEventRole"] = "PHYSICAL_SCANOUT"
        cases.append(wrong_role)
        global_details = self.observable_display()
        global_details["violations"] = [{"gate": "display", "reason": "hidden failure"}]
        cases.append(global_details)
        series_details = self.observable_display()
        series_details["series"][0]["violations"] = [{"gate": "timing", "reason": "hidden failure"}]
        cases.append(series_details)
        for display in cases:
            with self.assertRaises(QualificationContractError):
                validate_display_pass(display, RUN_ID, 19, policy=OBSERVABLE_POLICY)

    def test_observable_profile_rejects_mismatched_mode_or_policy_hash(self):
        display = self.observable_display()
        wrong_policy = {**OBSERVABLE_POLICY, "acceptanceMode": "OBSERVABLE_RENDER_V0"}
        with self.assertRaises(QualificationContractError):
            validate_display_pass(display, RUN_ID, 19, policy=wrong_policy)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy_path = root / "policy.json"
            policy_path.write_text(json.dumps(OBSERVABLE_POLICY), encoding="utf-8")
            digest = hashlib.sha256(policy_path.read_bytes()).hexdigest()
            attempt = {"policySha256": digest.upper()}
            display["policySha256"] = digest
            validate_policy_binding(policy_path, attempt, display)
            display["policySha256"] = "0" * 64
            with self.assertRaises(QualificationContractError):
                validate_policy_binding(policy_path, attempt, display)


if __name__ == "__main__":
    unittest.main()
