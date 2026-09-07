import json
import tempfile
import unittest
from pathlib import Path
from import_qualification_failures import (
    from_outcome,
    import_failures,
    import_single_episode_failures,
    sample_key,
)


class ImportFailureTest(unittest.TestCase):
    def test_single_outcome_is_preserved_without_inventing_a_five_episode_chain(self):
        item = self.single_outcome()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ("raw-outcomes.json", "outcomes.json"):
                (root / name).write_text(json.dumps([item]), encoding="utf-8")
            chains, unresolved = import_failures([root], [])
            singles, single_unresolved, _ = import_single_episode_failures([root])
            self.assertEqual([], chains)
            self.assertEqual([], unresolved + single_unresolved)
            self.assertEqual(1, len(singles))
            self.assertEqual("comic:10001", singles[0]["seriesKey"])
            self.assertEqual("1", singles[0]["episodeKey"])
            self.assertEqual(3, len(singles[0]["provenance"]))
            self.assertEqual({"outcomes.json", "raw-outcomes.json"}, {
                Path(p["artifact"]).name for p in singles[0]["provenance"]
                if p["classification"] == "SINGLE_EPISODE_RUN_FAILURE"})

    def test_single_role_does_not_hide_missing_identity_or_provenance(self):
        for field, value in (("episodeKey", ""), ("provenance", []),
                             ("classification", "unknown"), ("kind", "WEBTOON")):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                item = self.single_outcome()
                item[field] = value
                (root / "outcomes.json").write_text(json.dumps([item]), encoding="utf-8")
                chains, unresolved = import_failures([root], [])
                singles, single_unresolved, _ = import_single_episode_failures([root])
                self.assertEqual([], chains + singles)
                self.assertTrue(unresolved)
                self.assertTrue(single_unresolved)

    @staticmethod
    def single_outcome():
        return {"source": "wfwf", "kind": "COMIC", "seriesKey": "comic:10001",
                "episodeKey": "1", "role": "MANDATORY_SINGLE_EPISODE_NO_CORPUS_CREDIT",
                "classification": "SINGLE_EPISODE_REGRESSION", "passed": False,
                "failure": "Mandatory single-episode series disappeared or was duplicated",
                "provenance": [{"artifact": "original-summary.json",
                                "classification": "SINGLE_EPISODE_DEVICE_FAILURE",
                                "reason": ["Cold first frame exceeded 4000ms"]}]}

    def test_exact_chain_is_retained_without_inventing_live_titles(self):
        record = {"seriesKey": "/manhwa/1", "title": "work", "chain": [f"/manhwa/1/{i}" for i in range(5)]}
        sample = from_outcome(record, "fixture")
        self.assertEqual("ntk", sample["source"])
        self.assertEqual(tuple(record["chain"]), sample_key(sample)[-1])
        self.assertTrue(sample["refreshEpisodeTitlesFromExactLiveIds"])

    def test_missing_chain_cannot_be_replaced_with_another_episode(self):
        with self.assertRaises(ValueError):
            from_outcome({"seriesKey": "comic:1", "title": "work", "chain": ["1"]}, "fixture")

    def test_nested_malformed_sample_cannot_be_imported_as_a_regression(self):
        with self.assertRaises(ValueError):
            from_outcome({"sample": {"source": "ntk", "kind": "COMIC", "seriesKey": "/manhwa/1",
                                      "title": "work", "episodes": ["one"]}}, "fixture")

    def test_duplicate_existing_chain_merges_provenance_without_losing_history(self):
        sample = from_outcome({"seriesKey": "/manhwa/1", "title": "work",
                               "chain": [f"/manhwa/1/{i}" for i in range(5)]}, "fixture")
        entry = {"sample": sample, "role": "MANDATORY_REGRESSION_NO_CORPUS_CREDIT",
                 "provenance": [{"artifact": "first", "index": 0, "failure": "one"}]}
        duplicate = {"sample": sample, "role": "MANDATORY_REGRESSION_NO_CORPUS_CREDIT",
                     "provenance": [{"artifact": "second", "index": 1, "failure": "two"}]}

        merged, unresolved = import_failures([], [entry, duplicate])

        self.assertEqual([], unresolved)
        self.assertEqual(1, len(merged))
        self.assertEqual(2, len(merged[0]["provenance"]))

    def test_deferred_external_display_failure_is_not_a_historical_chain_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "outcomes.json").write_text(json.dumps([{
                "role": "CORPUS", "passed": False, "collectionCompleted": True,
                "failure": None,
            }]), encoding="utf-8")
            merged, unresolved = import_failures([root], [])

        self.assertEqual([], unresolved)
        self.assertEqual([], merged)

    def test_blank_failure_reason_is_unresolved_instead_of_dropping_the_chain(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "outcomes.json").write_text(json.dumps([{
                "passed": False, "failure": "", "seriesKey": "/manhwa/1",
                "title": "work", "chain": [f"/manhwa/1/{i}" for i in range(5)],
            }]), encoding="utf-8")
            merged, unresolved = import_failures([root], [])

        self.assertEqual([], merged)
        self.assertEqual(1, len(unresolved))
        self.assertIn("nonblank failure", unresolved[0]["reason"])

    def test_wfwf_summary_failures_are_separate_and_deduplicated(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index in range(2):
                artifact = root / f"wfwf-run-{index}" / "summary.json"
                artifact.parent.mkdir()
                artifact.write_text(json.dumps({
                    "sourceId": "wfwf",
                    "seriesKey": "comic:10007",
                    "episodeKey": "28",
                    "violations": [f"failure-{index}"],
                }), encoding="utf-8")

            records, unresolved, calibration_only = import_single_episode_failures([root])

        self.assertEqual([], unresolved)
        self.assertEqual([], calibration_only)
        self.assertEqual(1, len(records))
        self.assertEqual("MANDATORY_SINGLE_EPISODE_NO_CORPUS_CREDIT", records[0]["role"])
        self.assertNotIn("episodes", records[0])
        self.assertEqual(2, len(records[0]["provenance"]))

    def test_wfwf_diagnostic_failure_is_not_silently_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = root / "wfwf-diagnostic-v1"
            diagnostic = run / "diagnostic-wfwf" / "diagnostic.json"
            diagnostic.parent.mkdir(parents=True)
            diagnostic.write_text(json.dumps({
                "mode": "DIAGNOSTIC_NO_CORPUS_CREDIT",
                "source": "wfwf",
                "seriesKey": "comic:10007",
                "episodeKey": "28",
            }), encoding="utf-8")
            (run / "instrumentation.txt").write_text(
                "Ten-episode auto-append violations: Final page was not fully traversed\n"
                "FAILURES!!!\n", encoding="utf-8")

            records, unresolved, calibration_only = import_single_episode_failures([root])

        self.assertEqual([], unresolved)
        self.assertEqual([], calibration_only)
        self.assertEqual(1, len(records))
        self.assertEqual("28", records[0]["episodeKey"])
        self.assertTrue(any(item["classification"] == "SINGLE_EPISODE_DEVICE_FAILURE"
                            for item in records[0]["provenance"]))
        self.assertTrue(any(item["diagnosticArtifact"].endswith("diagnostic.json")
                            for item in records[0]["provenance"]))

    def test_wfwf_display_failure_is_bound_as_single_episode(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = root / "wfwf-diagnostic-v3"
            diagnostic = run / "diagnostic-wfwf" / "diagnostic.json"
            diagnostic.parent.mkdir(parents=True)
            diagnostic.write_text(json.dumps({
                "mode": "DIAGNOSTIC_NO_CORPUS_CREDIT",
                "source": "wfwf",
                "seriesKey": "comic:10007",
                "episodeKey": "28",
            }), encoding="utf-8")
            (run / "instrumentation.txt").write_text("OK (1 test)\n", encoding="utf-8")
            (run / "host-display-verification.json").write_text(json.dumps({
                "series": [{
                    "sampleKey": "diagnostic-wfwf",
                    "passed": False,
                    "requiresCalibration": True,
                    "violationCounts": {"calibration": 1, "coverage": 2},
                    "metrics": {"expectedPages": 40},
                    "violations": [{"gate": "coverage", "reason": "missing row"}],
                }],
            }), encoding="utf-8")
            (run / "diagnostic-wfwf" / "collection.json").write_text(json.dumps({
                "sampleKey": "diagnostic-wfwf",
            }), encoding="utf-8")

            records, unresolved, calibration_only = import_single_episode_failures([root])

        self.assertEqual([], unresolved)
        self.assertEqual([], calibration_only)
        self.assertEqual(1, len(records))
        self.assertTrue(any(item["classification"] == "SINGLE_EPISODE_DISPLAY_FAILURE"
                            for item in records[0]["provenance"]))

    def test_calibration_only_diagnostic_is_not_imported_as_episode_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = root / "wfwf-calibration-only"
            diagnostic = run / "diagnostic-wfwf" / "diagnostic.json"
            diagnostic.parent.mkdir(parents=True)
            diagnostic.write_text(json.dumps({
                "mode": "DIAGNOSTIC_NO_CORPUS_CREDIT",
                "source": "wfwf",
                "seriesKey": "comic:10007",
                "episodeKey": "28",
            }), encoding="utf-8")
            (run / "instrumentation.txt").write_text("OK (1 test)\n", encoding="utf-8")
            (run / "host-display-verification.json").write_text(json.dumps({
                "series": [{
                    "sampleKey": "diagnostic-wfwf",
                    "passed": False,
                    "requiresCalibration": True,
                    "violationCounts": {"calibration": 1},
                    "violations": [{"gate": "calibration", "reason": "independent proof required"}],
                }],
            }), encoding="utf-8")
            (run / "diagnostic-wfwf" / "collection.json").write_text(json.dumps({
                "sampleKey": "diagnostic-wfwf",
            }), encoding="utf-8")

            records, unresolved, calibration_only = import_single_episode_failures([root])

        self.assertEqual([], records)
        self.assertEqual([], unresolved)
        self.assertEqual(1, len(calibration_only))
        self.assertEqual("CALIBRATION_ONLY_NO_CORPUS_CREDIT", calibration_only[0]["classification"])

    def test_malformed_wfwf_single_episode_is_unresolved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "wfwf-run" / "summary.json"
            artifact.parent.mkdir()
            artifact.write_text(json.dumps({
                "sourceId": "wfwf",
                "seriesKey": "comic:10007",
                "violations": ["failure with no exact episode identity"],
            }), encoding="utf-8")

            records, unresolved, calibration_only = import_single_episode_failures([root])

        self.assertEqual([], records)
        self.assertEqual([], calibration_only)
        self.assertEqual(1, len(unresolved))
        self.assertEqual("UNIDENTIFIABLE_SINGLE_EPISODE", unresolved[0]["classification"])

    def test_chainless_random_200_wfwf_capture_is_explicitly_unresolved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "random-200-wfwf-comic-5-355165"
            artifact.mkdir()
            for name in ("frame-stats-summary.txt", "presentation-evidence.tsv",
                         "telemetry-timeline.txt"):
                (artifact / name).write_text("telemetry only", encoding="utf-8")

            records, unresolved, calibration_only = import_single_episode_failures([root])

        self.assertEqual([], records)
        self.assertEqual([], calibration_only)
        self.assertEqual(1, len(unresolved))
        self.assertEqual("UNIDENTIFIABLE_WFWF_HISTORY", unresolved[0]["classification"])
        self.assertIn("ordered five-episode chain", unresolved[0]["reason"])

    def test_failed_single_runner_artifact_merges_new_provenance_without_padding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "qualification-run" / "single-episode-failure.json"
            artifact.parent.mkdir()
            regression = {
                "source": "wfwf",
                "kind": "COMIC",
                "seriesKey": "comic:10007",
                "episodeKey": "28",
                "role": "MANDATORY_SINGLE_EPISODE_NO_CORPUS_CREDIT",
                "classification": "SINGLE_EPISODE_REGRESSION",
                "provenance": [{"artifact": "old", "classification": "SINGLE_EPISODE_DEVICE_FAILURE",
                                "reason": "old failure"}],
            }
            artifact.write_text(json.dumps({
                "sampleKey": "single-run-1",
                "role": "MANDATORY_SINGLE_EPISODE_NO_CORPUS_CREDIT",
                "singleEpisodeRegression": regression,
                "failure": "new failure",
            }), encoding="utf-8")

            records, unresolved, calibration_only = import_single_episode_failures([root])

        self.assertEqual([], unresolved)
        self.assertEqual([], calibration_only)
        self.assertEqual(1, len(records))
        self.assertEqual(2, len(records[0]["provenance"]))
        self.assertTrue(any(item["classification"] == "SINGLE_EPISODE_RUN_FAILURE"
                            for item in records[0]["provenance"]))


if __name__ == "__main__":
    unittest.main()
