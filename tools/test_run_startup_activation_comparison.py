"""Offline fail-closed tests for the startup activation host driver."""

from __future__ import annotations

import csv
import json
from pathlib import Path
import sys
import tempfile
import unittest

import run_startup_activation_comparison as driver


def contract_fixture() -> tuple[dict, dict]:
    formula = "(firstActualSubmittedAtNanos - entryRequestedAtNanos) / 1000000.0"
    order = [["EAGER", "FIRST_USE"] if pair % 2 else ["FIRST_USE", "EAGER"] for pair in range(1, 11)]
    policy = {
        "schemaVersion": 1,
        "name": "startup-activation-v1",
        "mode": "DIAGNOSTIC_NO_CORPUS_CREDIT",
        "methodFreeze": "FROZEN_BEFORE_MEASUREMENTS",
        "conditionA": "EAGER",
        "conditionB": "FIRST_USE",
        "primaryMetric": "firstActualSubmissionFromEntryMs",
        "primaryFormula": formula,
        "pairedDifference": "FIRST_USE minus EAGER; positive favors EAGER",
        "pairs": {
            "initial": 5,
            "maximum": 10,
            "order": order,
            "extensionRule": "Collect pairs 1..5 first. Collect pairs 6..10 only if the two-sided 95% Student t CI of the five paired differences includes zero.",
        },
        "preconditions": [
            "No cache clearing", "No cache injection", "Existing complete NTK snapshot",
            "No provider prepare", "COMPLETE_LEASE_OPENED", "unchanged",
        ],
        "measurement": {"physicalPresentationQualified": False, "corpusCredit": 0},
        "outputContract": "STARTUP-CONTRACT.json",
    }
    fields = [
        "schemaVersion", "diagnosticOnly", "corpusCredit", "pair", "trialInPair", "mode", "apkSha256",
        "episode", "savedPosition", "cacheBefore", "cacheAfter", "cacheUnchanged", "cachedResume",
        "timestamps", "durationsMs", "initialResponseStartedAtNanos", "observedAnchor", "candidate",
        "physicalPresentationQualified",
    ]
    contract = {
        "schemaVersion": 1,
        "owner": "STARTUP",
        "status": "READY_FOR_EXECUTION",
        "methodFreeze": "FROZEN_BEFORE_MEASUREMENTS",
        "testSelector": "ml.melun.mangaview.viewer.StartupActivationBenchmarkTest#captureSingleStartupTrial",
        "negativeControlSelector": "ml.melun.mangaview.viewer.StartupActivationBenchmarkPolicyTest",
        "oneTrialPerInstrumentationInvocation": True,
        "arguments": {
            "startupMode": {"required": True, "values": ["EAGER", "FIRST_USE"]},
            "startupPair": {"required": True, "type": "integer", "range": "1..10"},
            "startupTrial": {"required": True, "type": "integer", "values": [0, 1]},
            "startupSeriesKey": {"required": False, "default": driver.SERIES_KEY},
            "startupEpisodeKey": {"required": False, "default": driver.EPISODE_KEY},
            "startupArtifactPrefix": {"required": False, "default": "startup-activation-comparison"},
        },
        "frozenMethod": {
            "endpoint": "readableActualContent fullVisualCoverage fullActualCoverage submittedAtNanos>0 bufferFrameId>0 timestampKind is not CANCELLED/DROPPED/CONTEXT_LOST matching PresentedImageRegion verified image identity",
            "cache": "app_complete_resume_v1 COMPLETE_LEASE_OPENED manifestPageCount=132 unchanged initialResponseStartedAtNanos=null",
            "timestamps": ["entryRequestedAtNanos", "openStartedAtNanos", "manifestReadyAtNanos", "firstActualSubmittedAtNanos", "firstProxyTimestampNanos"],
        },
        "primaryMetric": {"name": "firstActualSubmissionFromEntryMs", "formula": formula, "pairedDifference": policy["pairedDifference"]},
        "policy": {"initialPairs": 5, "maximumPairs": 10, "credit": "diagnostic only; corpusCredit=0; physicalPresentationQualified=false"},
        "outputs": {
            "root": driver.REMOTE_ARTIFACT_ROOT + "/",
            "perTrial": ["trial.json", "presentation-evidence.tsv"],
            "failure": "trial-failure.json",
            "trialJsonRequiredFields": fields,
        },
    }
    return contract, policy


def trial_fixture(root: Path, *, mode: str = "EAGER", pair: int = 1, trial_in_pair: int = 0) -> dict:
    episode = {"sourceId": "ntk", "seriesKey": driver.SERIES_KEY, "episodeKey": driver.EPISODE_KEY}
    position = {"pageKey": "p0007", "offsetInPageUnits": 321}
    before = {"path": "/data/user/0/ml.melun.mangav/app_complete_resume_v1/complete.snapshot", "exists": True, "byteCount": 100, "sha256": "a" * 64}
    after = dict(before)
    entry = 1_000_000_000
    submitted = entry + 25_000_000
    candidate = {
        "token": 7, "rendererIdentity": 3, "generation": 4, "anchorOffsetUnits": 321,
        "bufferFrameId": 12, "regionPageKey": "p0007", "regionImageIdentityVerified": True,
        "readableActualContent": True, "fullVisualCoverage": True, "fullActualCoverage": True,
    }
    trial = {
        "schemaVersion": 1, "diagnosticOnly": True, "corpusCredit": 0, "valid": True,
        "pair": pair, "trialInPair": trial_in_pair, "mode": mode, "apkSha256": "b" * 64,
        "episode": episode, "savedPosition": position, "cacheBefore": before, "cacheAfter": after,
        "cacheUnchanged": True, "cachedResume": {"route": "COMPLETE_LEASE_OPENED", "episode": episode,
                                                     "manifestPageCount": 132, "routeAtNanos": 1_100_000_000},
        "timestamps": {"entryRequestedAtNanos": entry, "openStartedAtNanos": entry + 1_000_000,
                       "manifestReadyAtNanos": entry + 2_000_000, "firstActualSubmittedAtNanos": submitted,
                       "firstProxyTimestampNanos": None, "firstProxyTimestampKind": None},
        "durationsMs": {"firstActualSubmissionFromEntryMs": 25.0},
        "initialResponseStartedAtNanos": None, "observedAnchor": position, "observedUserInputRevision": 0,
        "candidate": candidate, "physicalPresentationQualified": False,
    }
    tsv = root / "presentation-evidence.tsv"
    with tsv.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=[
            "index", "rendererIdentity", "token", "generation", "presentedNanos", "submittedAtNanos",
            "renderLatencyNanos", "readableActualContent", "fullVisualCoverage", "fullActualCoverage",
            "timestampKind", "bufferFrameId", "anchorOrdinal", "anchorOffsetUnits", "geometryRevision",
            "userInputRevision",
        ], delimiter="\t")
        writer.writeheader()
        writer.writerow({"index": 0, "rendererIdentity": 3, "token": 7, "generation": 4,
                         "presentedNanos": 1_030_000_000, "submittedAtNanos": submitted,
                         "renderLatencyNanos": 1_000_000, "readableActualContent": "true",
                         "fullVisualCoverage": "true", "fullActualCoverage": "true",
                         "timestampKind": "COMPOSITION_LATCH", "bufferFrameId": 12,
                         "anchorOrdinal": 7, "anchorOffsetUnits": 321, "geometryRevision": 1,
                         "userInputRevision": 0})
    return trial


class StartupDriverHostTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        raw_contract, raw_policy = contract_fixture()
        self.contract = driver.validate_contract(raw_contract, raw_policy)
        self.trial_dir = self.root / "trial"
        self.trial_dir.mkdir()
        self.trial = trial_fixture(self.trial_dir)

    def tearDown(self):
        self.temp.cleanup()

    def test_current_frozen_schedule_is_alternating(self):
        self.assertEqual(self.contract.pair_order[0], ("EAGER", "FIRST_USE"))
        self.assertEqual(self.contract.pair_order[1], ("FIRST_USE", "EAGER"))
        self.assertEqual(len(self.contract.pair_order), 10)

    def test_primary_metric_does_not_fall_back_to_open(self):
        contract, policy = contract_fixture()
        contract["primaryMetric"]["formula"] = "(firstActualSubmittedAtNanos - openStartedAtNanos) / 1000000.0"
        policy["primaryFormula"] = contract["primaryMetric"]["formula"]
        with self.assertRaisesRegex(driver.ContractError, "shared entry boundary"):
            driver.validate_contract(contract, policy)

    def test_valid_trial_requires_complete_same_session_lease(self):
        result = driver.validate_trial(
            self.trial_dir, {**self.trial, "apkSha256": "b" * 64}, self.contract,
            pair=1, trial_in_pair=0, mode="EAGER", apk_sha256="b" * 64, expected_position=None,
        )
        self.assertEqual(result["primaryMetricValueMs"], 25.0)
        bad = json.loads(json.dumps(self.trial))
        bad["cachedResume"]["route"] = "SOURCE_FALLBACK"
        with self.assertRaisesRegex(driver.TrialError, "COMPLETE_LEASE_OPENED"):
            driver.validate_trial(self.trial_dir, bad, self.contract, pair=1, trial_in_pair=0,
                                  mode="EAGER", apk_sha256="b" * 64, expected_position=None)

    def test_first_valid_trial_binds_position_and_cache_for_later_trials(self):
        first = driver.validate_trial(
            self.trial_dir, self.trial, self.contract,
            pair=1, trial_in_pair=0, mode="EAGER", apk_sha256="b" * 64, expected_position=None,
        )
        bad_position = json.loads(json.dumps(self.trial))
        bad_position["savedPosition"]["pageKey"] = "p0008"
        with self.assertRaisesRegex(driver.TrialError, "requested exact saved position"):
            driver.validate_trial(
                self.trial_dir, bad_position, self.contract,
                pair=1, trial_in_pair=0, mode="EAGER", apk_sha256="b" * 64,
                expected_position=first["savedPosition"], expected_cache_sha256=first["cacheSha256"],
            )
        bad_cache = json.loads(json.dumps(self.trial))
        bad_cache["cacheBefore"]["sha256"] = "c" * 64
        bad_cache["cacheAfter"]["sha256"] = "c" * 64
        with self.assertRaisesRegex(driver.TrialError, "frozen first trial"):
            driver.validate_trial(
                self.trial_dir, bad_cache, self.contract,
                pair=1, trial_in_pair=0, mode="EAGER", apk_sha256="b" * 64,
                expected_position=first["savedPosition"], expected_cache_sha256=first["cacheSha256"],
            )

    def test_cancelled_dropped_zero_buffer_and_wrong_page_candidates_fail(self):
        for mutation, message in (
            (lambda trial: trial["candidate"].update(bufferFrameId=0), "candidate.bufferFrameId"),
            (lambda trial: trial["candidate"].update(regionPageKey="p0008"), "wrong page"),
        ):
            bad = json.loads(json.dumps(self.trial))
            mutation(bad)
            with self.assertRaises(driver.TrialError, msg=message):
                driver.validate_trial(self.trial_dir, bad, self.contract, pair=1, trial_in_pair=0,
                                      mode="EAGER", apk_sha256="b" * 64, expected_position=None)
        bad_tsv = self.trial_dir / "presentation-evidence.tsv"
        original_tsv = bad_tsv.read_text(encoding="utf-8")
        for timestamp_kind in ("CANCELLED", "DROPPED", "CONTEXT_LOST"):
            bad_tsv.write_text(original_tsv.replace("COMPOSITION_LATCH", timestamp_kind), encoding="utf-8")
            with self.assertRaisesRegex(driver.TrialError, "strict native candidate"):
                driver.validate_trial(self.trial_dir, self.trial, self.contract, pair=1, trial_in_pair=0,
                                      mode="EAGER", apk_sha256="b" * 64, expected_position=None)

    def test_physical_or_corpus_claim_fails_closed(self):
        bad = json.loads(json.dumps(self.trial))
        bad["corpusCredit"] = 1
        with self.assertRaisesRegex(driver.TrialError, "physical-display or corpus"):
            driver.validate_trial(self.trial_dir, bad, self.contract, pair=1, trial_in_pair=0,
                                  mode="EAGER", apk_sha256="b" * 64, expected_position=None)

    def test_extension_decision_uses_frozen_primary_and_ci_rule(self):
        records = []
        for pair in range(1, 6):
            records.extend([
                {"pair": pair, "mode": "EAGER", "primaryMetricValueMs": 10.0},
                {"pair": pair, "mode": "FIRST_USE", "primaryMetricValueMs": 10.0},
            ])
        decision = driver.extension_decision(records, self.contract)
        self.assertTrue(decision["includesZero"])
        self.assertEqual(decision["metric"], "firstActualSubmissionFromEntryMs")

    def test_instrumentation_success_is_not_exit_code_only(self):
        self.assertEqual(
            driver.validate_instrumentation_result(
                driver.CommandResult(0, b"INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")
            ),
            1,
        )
        for output in (
            b"INSTRUMENTATION_FAILED: timeout\nOK (1 test)\n",
            b"INSTRUMENTATION_STATUS_CODE: -1\nOK (1 test)\n",
            b"FAILURES!!!\n",
        ):
            with self.assertRaises(driver.TrialError):
                driver.validate_instrumentation_result(driver.CommandResult(0, output))

    def test_timeout_preserves_partial_output_and_terminates_owned_process(self):
        with self.assertRaises(driver.CommandTimeout) as raised:
            driver.SubprocessRunner().run(
                [sys.executable, "-c", "import time; print('partial-output', flush=True); time.sleep(2)"],
                timeout=0.05,
            )
        self.assertTrue(raised.exception.termination_verified)
        self.assertIn("partial-output", raised.exception.result.stdout.decode(errors="replace"))

    def test_execute_freezes_first_valid_position_and_cache_before_next_trial(self):
        apk = self.root / "candidate.apk"
        apk.write_bytes(b"candidate")
        output = self.root / "run"
        comparison = driver.StartupComparisonDriver(
            self.contract, apk=apk, output=output, adb_executable="adb", pairs=5,
            seed=11, expected_position=None, timeout_seconds=1, runner=object(),
        )
        comparison.preflight = lambda _apk_sha256: {"deviceProcessBlockers": [], "hostProcessBlockers": []}
        calls = []

        def fake_trial(pair, trial_in_pair, mode, _apk_sha256):
            calls.append((pair, trial_in_pair, mode, comparison.expected_position, comparison.expected_cache_sha256))
            return {
                "pair": pair, "trialInPair": trial_in_pair, "mode": mode, "primaryMetricValueMs": 10.0,
                "savedPosition": {"pageKey": "p0007", "offsetInPageUnits": 321},
                "cacheSha256": "a" * 64, "physicalPresentationQualified": False, "corpusCredit": 0,
            }

        comparison._run_one_trial = fake_trial
        comparison.execute()
        self.assertIsNone(calls[0][3])
        self.assertIsNone(calls[0][4])
        self.assertEqual(calls[1][3], {"pageKey": "p0007", "offsetInPageUnits": 321})
        self.assertEqual(calls[1][4], "a" * 64)
        self.assertEqual(comparison.expected_position, calls[1][3])
        self.assertEqual(comparison.expected_cache_sha256, "a" * 64)

    def test_final_ten_pair_conclusion_uses_df9(self):
        records = []
        differences = [1.0, -1.0, 0.5, -0.5, 1.5, -1.5, 0.25, -0.25, 0.75, -0.75]
        for pair, difference in enumerate(differences, start=1):
            records.extend([
                {"pair": pair, "mode": "EAGER", "primaryMetricValueMs": 10.0},
                {"pair": pair, "mode": "FIRST_USE", "primaryMetricValueMs": 10.0 + difference},
            ])
        decision = driver.final_ten_pair_decision(records, self.contract)
        self.assertEqual(decision["degreesOfFreedom"], 9)
        self.assertAlmostEqual(decision["criticalT95"], driver.T_CRITICAL_95_DF9)
        self.assertTrue(decision["includesZero"])

    def test_plan_preserves_exact_position_without_inventing_one(self):
        plan = driver.build_plan(self.contract, pairs=5, seed=7, expected_position=None)
        self.assertIsNone(plan["expectedSavedPosition"])
        with_position = driver.build_plan(self.contract, pairs=5, seed=7,
                                          expected_position={"pageKey": "p0007", "offsetInPageUnits": 321})
        self.assertEqual(with_position["expectedSavedPosition"]["pageKey"], "p0007")


if __name__ == "__main__":
    unittest.main()
