"""Host-only protocol tests for qualification_segments.py."""

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

try:
    from qualification_segments import (
        CHECKPOINT_STATUS,
        OBSERVABLE_ACCEPTANCE_MODE,
        OBSERVABLE_ACCEPTED_RESULT,
        OBSERVABLE_QUALIFICATION_CLAIM,
        PHYSICAL_METRIC_FIELDS,
        PRESENT_EVENT_ORIGIN,
        PRESENT_EVENT_ROLE,
        SegmentError,
        UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
        aggregate_segments,
        sha256_file,
        verify_segment,
    )
except ModuleNotFoundError:
    from tools.qualification_segments import (
        CHECKPOINT_STATUS,
        OBSERVABLE_ACCEPTANCE_MODE,
        OBSERVABLE_ACCEPTED_RESULT,
        OBSERVABLE_QUALIFICATION_CLAIM,
        PHYSICAL_METRIC_FIELDS,
        PRESENT_EVENT_ORIGIN,
        PRESENT_EVENT_ROLE,
        SegmentError,
        UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
        aggregate_segments,
        sha256_file,
        verify_segment,
    )


RUN_ID = "run-fixture"
ATTEMPT_SHA = "a" * 64
SAFE_NONCE = "nonce-1"
OBSERVABLE_POLICY = {
    "exceptions": [],
    "acceptanceMode": OBSERVABLE_ACCEPTANCE_MODE,
    "physicalPresentationTiming": UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
}


class QualificationSegmentsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.policy_path = self.root / "policy.json"
        self.policy_path.write_text(json.dumps(OBSERVABLE_POLICY, separators=(",", ":")), encoding="utf-8")
        self.policy_sha = sha256_file(self.policy_path)
        self.verifier_path = self.root / "verify_display_trace.py"
        self.verifier_path.write_text("# immutable verifier fixture\n", encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def make_sample(self, sample_key="sample-1", *, evidence_name=None):
        evidence = self.root / (evidence_name or f"evidence-{sample_key}")
        capture = evidence / "capture"
        capture.mkdir(parents=True)
        (capture / "collection.json").write_text(
            json.dumps({"sampleKey": sample_key, "requiredEpisodes": 5}), encoding="utf-8")
        trace = self.root / f"trace-{sample_key}.pftrace"
        trace.write_bytes(b"flushed trace bytes")
        checkpoint = self.root / f"checkpoint-{sample_key}.json"
        checkpoint.write_text(json.dumps({
            "schema": 1,
            "runId": RUN_ID,
            "sampleKey": sample_key,
            "attemptSha256": ATTEMPT_SHA,
            "policySha256": self.policy_sha,
            "checkpointNonce": SAFE_NONCE,
            "status": CHECKPOINT_STATUS,
        }, separators=(",", ":")), encoding="utf-8")
        return checkpoint, trace, evidence

    def report(self, sample_key, *, passed=True):
        segment = {
            "sampleKey": sample_key,
            "passed": passed,
            "displayEvidenceComplete": False,
            "observableEvidenceComplete": passed,
            "result": OBSERVABLE_ACCEPTED_RESULT if passed else "OBSERVABLE_RENDER_V1_REJECTED",
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
            "physicalMetrics": {field: None for field in PHYSICAL_METRIC_FIELDS},
            "metrics": {"expectedPages": 1, "fullyCoveredPages": 1},
            "displayCorrelationComplete": passed,
            "coverageComplete": passed,
            "timingComplete": passed,
            "noAutoJumpEvidenceComplete": passed,
            "violations": [] if passed else [{"gate": "coverage", "reason": "fixture failure"}],
            "violationCounts": {} if passed else {"coverage": 1},
        }
        return {
            "schemaVersion": 1,
            "passed": passed,
            "displayEvidenceComplete": False,
            "observableEvidenceComplete": passed,
            "result": OBSERVABLE_ACCEPTED_RESULT if passed else "OBSERVABLE_RENDER_V1_REJECTED",
            "acceptanceMode": OBSERVABLE_ACCEPTANCE_MODE,
            "physicalPresentationTiming": UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
            "exactPhysicalPresentationTimeVerified": False,
            "displayCorrelationComplete": passed,
            "coverageComplete": passed,
            "timingComplete": passed,
            "noAutoJumpEvidenceComplete": passed,
            "physicalTimingComplete": False,
            "requiresCalibration": True,
            "policySha256": self.policy_sha,
            "measurementUncertainty": segment["measurementUncertainty"],
            "physicalMetrics": segment["physicalMetrics"],
            "violations": segment["violations"],
            "violationCounts": segment["violationCounts"],
            "series": [segment],
            "qualificationClaim": OBSERVABLE_QUALIFICATION_CLAIM,
        }

    def pass_runner(self, command):
        output = Path(command[command.index("--output") + 1])
        evidence = Path(command[command.index("--evidence-directory") + 1])
        trace = Path(command[command.index("--trace") + 1])
        collection = next(evidence.rglob("collection.json"))
        sample_key = json.loads(collection.read_text(encoding="utf-8"))["sampleKey"]
        report = self.report(sample_key)
        report["traceSha256"] = sha256_file(trace)
        report["verifierSha256"] = sha256_file(self.verifier_path)
        output.write_text(json.dumps(report), encoding="utf-8")
        return SimpleNamespace(returncode=0, stdout="verifier ok", stderr="")

    def run_sample(self, sample_key, *, output_name=None, runner=None):
        checkpoint, trace, evidence = self.make_sample(sample_key)
        output = self.root / (output_name or f"segment-{sample_key}")
        return verify_segment(
            checkpoint, RUN_ID, ATTEMPT_SHA, self.policy_sha, trace, evidence,
            self.policy_path, output, verifier_path=self.verifier_path,
            verifier_runner=runner or self.pass_runner,
        )

    def test_pass_writes_barrier_compatible_verdict_and_manifest(self):
        result = self.run_sample("sample-1")
        self.assertTrue(result["passed"])
        verdict = json.loads(result["verdictPath"].read_text(encoding="utf-8"))
        self.assertEqual(verdict["schema"], 1)
        self.assertEqual(verdict["runId"], RUN_ID)
        self.assertEqual(verdict["sampleKey"], "sample-1")
        self.assertEqual(verdict["attemptSha256"], ATTEMPT_SHA)
        self.assertEqual(verdict["policySha256"], self.policy_sha)
        self.assertEqual(verdict["checkpointNonce"], SAFE_NONCE)
        self.assertTrue(verdict["passed"])
        self.assertEqual(len(verdict["checkpointSha256"]), 64)
        manifest = result["manifest"]
        self.assertEqual(manifest["traceSha256"], sha256_file(Path(manifest["tracePath"])))
        self.assertEqual(manifest["reportSha256"], sha256_file(Path(manifest["reportPath"])))
        self.assertEqual(manifest["verifierSha256"], sha256_file(Path(manifest["verifierPath"])))

    def test_schema_must_be_integer_not_boolean_or_fraction(self):
        for index, schema in enumerate((True, 1.0, 1.5, "1")):
            with self.subTest(schema=schema):
                checkpoint, trace, evidence = self.make_sample(f"schema-{index}")
                value = json.loads(checkpoint.read_text(encoding="utf-8"))
                value["schema"] = schema
                checkpoint.write_text(json.dumps(value), encoding="utf-8")
                with self.assertRaisesRegex(SegmentError, "schema"):
                    verify_segment(checkpoint, RUN_ID, ATTEMPT_SHA, self.policy_sha, trace, evidence,
                                   self.policy_path, self.root / f"schema-output-{index}",
                                   verifier_path=self.verifier_path, verifier_runner=self.pass_runner)

    def test_stale_nonce_is_rejected_and_checkpoint_mutation_fails_closed(self):
        checkpoint, trace, evidence = self.make_sample("sample-1")
        with self.assertRaisesRegex(SegmentError, "stale"):
            verify_segment(checkpoint, RUN_ID, ATTEMPT_SHA, self.policy_sha, trace, evidence,
                           self.policy_path, self.root / "stale", expected_checkpoint_nonce="old",
                           verifier_path=self.verifier_path, verifier_runner=self.pass_runner)

        def mutate_checkpoint(command):
            checkpoint.write_text(checkpoint.read_text(encoding="utf-8") + " ", encoding="utf-8")
            return self.pass_runner(command)

        result = verify_segment(
            checkpoint, RUN_ID, ATTEMPT_SHA, self.policy_sha, trace, evidence,
            self.policy_path, self.root / "mutated", verifier_path=self.verifier_path,
            verifier_runner=mutate_checkpoint,
        )
        self.assertFalse(result["passed"])
        self.assertFalse(result["verdict"]["passed"])
        self.assertTrue(any("checkpoint bytes changed" in item for item in result["manifest"]["errors"]))
        self.assertTrue((result["manifestPath"].parent / "verifier-report.raw.json").is_file())

    def test_mismatched_policy_or_sample_is_rejected(self):
        checkpoint, trace, evidence = self.make_sample("sample-1")
        with self.assertRaisesRegex(SegmentError, "policySha256"):
            verify_segment(checkpoint, RUN_ID, ATTEMPT_SHA, "b" * 64, trace, evidence,
                           self.policy_path, self.root / "policy-mismatch",
                           verifier_path=self.verifier_path, verifier_runner=self.pass_runner)
        (evidence / "capture" / "collection.json").write_text(
            json.dumps({"sampleKey": "different"}), encoding="utf-8")
        with self.assertRaisesRegex(SegmentError, "sampleKey"):
            verify_segment(checkpoint, RUN_ID, ATTEMPT_SHA, self.policy_sha, trace, evidence,
                           self.policy_path, self.root / "sample-mismatch",
                           verifier_path=self.verifier_path, verifier_runner=self.pass_runner)

    def test_missing_and_duplicate_collections_are_rejected(self):
        checkpoint, trace, evidence = self.make_sample("sample-1")
        (evidence / "capture" / "collection.json").unlink()
        with self.assertRaisesRegex(SegmentError, "exactly one"):
            verify_segment(checkpoint, RUN_ID, ATTEMPT_SHA, self.policy_sha, trace, evidence,
                           self.policy_path, self.root / "missing",
                           verifier_path=self.verifier_path, verifier_runner=self.pass_runner)

        checkpoint, trace, evidence = self.make_sample("sample-2")
        duplicate = evidence / "second"
        duplicate.mkdir()
        (duplicate / "collection.json").write_text(json.dumps({"sampleKey": "sample-2"}), encoding="utf-8")
        with self.assertRaisesRegex(SegmentError, "exactly one"):
            verify_segment(checkpoint, RUN_ID, ATTEMPT_SHA, self.policy_sha, trace, evidence,
                           self.policy_path, self.root / "duplicate",
                           verifier_path=self.verifier_path, verifier_runner=self.pass_runner)

    def test_failed_verifier_persists_report_and_failed_verdict(self):
        def failed_runner(command):
            output = Path(command[command.index("--output") + 1])
            evidence = Path(command[command.index("--evidence-directory") + 1])
            trace = Path(command[command.index("--trace") + 1])
            collection = next(evidence.rglob("collection.json"))
            sample_key = json.loads(collection.read_text(encoding="utf-8"))["sampleKey"]
            report = self.report(sample_key, passed=False)
            report["traceSha256"] = sha256_file(trace)
            report["verifierSha256"] = sha256_file(self.verifier_path)
            output.write_text(json.dumps(report), encoding="utf-8")
            return SimpleNamespace(returncode=1, stdout="failed", stderr="display failure")

        result = self.run_sample("sample-failed", runner=failed_runner)
        self.assertFalse(result["passed"])
        self.assertTrue(result["reportPath"].is_file())
        self.assertFalse(result["verdict"]["passed"])
        self.assertEqual(result["manifest"]["verifierExitCode"], 1)

    def test_aggregate_preserves_limitation_and_rejects_duplicates(self):
        first = self.run_sample("sample-1", output_name="segment-1")
        second = self.run_sample("sample-2", output_name="segment-2")
        aggregate = aggregate_segments([first["manifestPath"], second["manifestPath"]])
        self.assertTrue(aggregate["passed"])
        self.assertEqual(aggregate["segmentCount"], 2)
        self.assertEqual([item["sampleKey"] for item in aggregate["series"]], ["sample-1", "sample-2"])
        self.assertIsNone(aggregate["traceSha256"])
        self.assertTrue(aggregate["segmentOnly"])
        self.assertTrue(aggregate["qualificationContractRequired"])
        self.assertTrue(all(value is None for value in aggregate["physicalMetrics"].values()))
        with self.assertRaisesRegex(SegmentError, "duplicate sampleKey"):
            aggregate_segments([first["manifestPath"], first["manifestPath"]])

    def test_aggregate_rejects_trace_evidence_report_policy_and_verifier_mutation(self):
        mutations = {
            "trace": lambda result: Path(result["manifest"]["tracePath"]).write_bytes(b"mutated trace"),
            "evidence": lambda result: Path(result["manifest"]["evidenceDirectory"], "capture", "extra.bin").write_bytes(b"x"),
            "report": lambda result: Path(result["manifest"]["reportPath"]).write_text("{}", encoding="utf-8"),
            "policy": lambda result: self.policy_path.write_text(self.policy_path.read_text(encoding="utf-8") + " ", encoding="utf-8"),
            "verifier": lambda result: self.verifier_path.write_text("mutated verifier", encoding="utf-8"),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                self.tearDown()
                self.setUp()
                result = self.run_sample(f"sample-{label}", output_name=f"segment-{label}")
                mutate(result)
                with self.assertRaisesRegex(SegmentError, "mutated"):
                    aggregate_segments([result["manifestPath"]])


if __name__ == "__main__":
    unittest.main()
