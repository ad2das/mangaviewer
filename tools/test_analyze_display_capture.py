"""Independent observation integrity and ambiguity tests; no hardware claims."""

import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image

from analyze_display_capture import analyze, clock_mapping, intersect, monotone_candidates, read_rgb, trace_probe_frames
from verify_display_trace import Findings, sha256


NATIVE = 10_000_000_000
EPOCH_OFFSET = 1_780_000_000_000_000_000


class ClockAndCandidateTest(unittest.TestCase):
    def test_host_guest_skew_and_millisecond_quantization_are_both_retained(self):
        probe = {"clockPairs": [{"nativeBeforeNanos": NATIVE, "epochMillis": (NATIVE + EPOCH_OFFSET) // 1_000_000,
                                 "nativeAfterNanos": NATIVE + 20_000}]}
        host = [{"hostBeforeEpochNanos": EPOCH_OFFSET + NATIVE + 120_000_000,
                 "guestEpochNanos": EPOCH_OFFSET + NATIVE,
                 "hostAfterEpochNanos": EPOCH_OFFSET + NATIVE + 150_000_000}]
        result = clock_mapping(probe, host)
        self.assertEqual(result["hostEpochMinusNativeNanos"],
                         (EPOCH_OFFSET + 119_980_000, EPOCH_OFFSET + 151_000_000))

    def test_guest_epoch_pair_alone_does_not_synchronize_host(self):
        result = clock_mapping({"clockPairs": [{"nativeBeforeNanos": 100, "nativeAfterNanos": 200, "epochMillis": 1000}]}, None)
        self.assertFalse(result["independentlyBracketed"])
        self.assertIsNone(result["hostEpochMinusNativeNanos"])

    def test_disjoint_clock_brackets_cannot_be_averaged_into_a_mapping(self):
        self.assertIsNone(intersect([(1, 4), (10, 15)]))

    def test_repeated_pattern_keeps_every_compatible_scene(self):
        self.assertEqual(monotone_candidates([[0, 4], [1, 5], [2, 6]]), [[0, 4], [1, 5], [2, 6]])

    def test_independent_order_can_disambiguate_without_estimated_timestamps(self):
        self.assertEqual(monotone_candidates([[0, 4], [1, 5], [2]]), [[0], [1], [2]])


class NestedTraceClockTest(unittest.TestCase):
    def setUp(self):
        self.markers = []
        self.events = []
        self.frames = []
        for index in range(2):
            native = NATIVE + index * 20_000_000
            child = native + 1_000_500
            self.markers.append(dict(id=index + 1, name=f"viewer_swap:{index+1}:{index+1}:{native}",
                                     ts=child, dur=1000, track_id=1, pid=100, process_name="zygote64",
                                     parent_name="viewer_clock", parent_track_id=1,
                                     parent_ts=native + 999_500, parent_dur=3000))
            self.frames.append(dict(token=index + 1, bufferFrameId=index + 1,
                                    submittedAtNanos=native - 1000, renderLatencyNanos=5000))
            for item, name in enumerate(["Queue", "PresentFenceSignaled"]):
                self.events.append(dict(id=10 * index + item, ts=child + 200 + item * 100,
                                        name=name, frame_number=index + 1,
                                        layer_name="SurfaceView[ml.melun.mangaview/ml.melun.mangaview.viewer.runtime.OwnedRendererProbeActivity](BLAST)#1"))

    def analyze_trace(self):
        markers, events = self.markers, self.events

        class Processor:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def query(self, sql):
                rows = markers if "FROM slice s" in sql else events if "FROM frame_slice" in sql else []
                return [SimpleNamespace(**row) for row in rows]

        findings = Findings()
        with patch("perfetto.trace_processor.TraceProcessor", return_value=Processor()):
            mapped, interval = trace_probe_frames(Path("synthetic.pftrace"), self.frames, findings, process_pid=100)
        return mapped, interval, findings

    def test_nested_preemption_is_measured_not_rejected_by_a_fixed_skew_limit(self):
        self.markers[1]["ts"] += 5_000_000
        self.markers[1]["parent_dur"] += 5_000_000
        self.frames[1]["renderLatencyNanos"] += 5_000_000
        for event in self.events[2:]:
            event["ts"] += 5_000_000
        mapped, interval, findings = self.analyze_trace()
        self.assertEqual(len(mapped), 2)
        self.assertEqual(interval, (999_500, 1_000_500))
        self.assertFalse(findings.counts)

    def test_disjoint_native_trace_brackets_remain_unresolved(self):
        self.markers[1]["ts"] += 10_000
        self.markers[1]["parent_ts"] += 10_000
        for event in self.events[2:]:
            event["ts"] += 10_000
        _mapped, interval, findings = self.analyze_trace()
        self.assertIsNone(interval)
        self.assertIn("trace", findings.counts)

    def test_legacy_child_without_required_clock_parent_is_rejected(self):
        self.markers[0].pop("parent_name")
        mapped, _interval, findings = self.analyze_trace()
        self.assertEqual(len(mapped), 1)
        self.assertIn("trace", findings.counts)


class RgbCaptureAnalysisTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.capture = self.root / "capture"
        self.probe = self.root / "probe"
        self.capture.mkdir()
        self.probe.mkdir()
        source = np.zeros((400, 200, 3), dtype=np.uint8)
        for band in range(8):
            source[band * 50:(band + 1) * 50] = [(37 * band + 41) % 256, (83 * band + 97) % 256, (149 * band + 23) % 256]
        Image.fromarray(source).save(self.probe / "source.jpg", quality=100, subsampling=0)
        self.source = np.asarray(Image.open(self.probe / "source.jpg").convert("RGB"))
        self.metadata = {"sourceWidthPx": 200, "sourceHeightPx": 400, "displayWidthPx": 200, "displayHeightPx": 500,
                         "surfaceBounds": {"left": 0, "top": 20, "right": 200, "bottom": 480},
                         "clockPairs": [{"nativeBeforeNanos": NATIVE, "nativeAfterNanos": NATIVE + 10_000,
                                         "epochMillis": (NATIVE + EPOCH_OFFSET) // 1_000_000}]}
        self.native = [{"token": i + 1, "bufferFrameId": i + 1, "submittedAtNanos": NATIVE + i * 16_000_000,
                        "renderLatencyNanos": 1_000_000, "scrollOffsetPx": i * 24, "sceneRevision": 1}
                       for i in range(4)]
        self.observations = []
        for index, frame in enumerate(self.native):
            # Independent full RGB reference: paste repeating decoded source rows
            # into the real viewport, then scale the complete display for capture.
            display = np.zeros((500, 200, 3), dtype=np.uint8)
            scroll = int(frame["scrollOffsetPx"])
            for y in range(20, 480):
                display[y] = self.source[(y - 20 + scroll) % 400]
            image = np.asarray(Image.fromarray(display).resize((100, 250), Image.Resampling.BILINEAR))
            path = self.capture / f"frame-{index:06d}.rgb"
            image[::-1].tofile(path)
            self.observations.append({"sequence": index, "file": path.name, "sha256": sha256(path),
                                      "width": 100, "height": 250, "pixelOrder": "left-to-right,bottom-up",
                                      "estimatedGenerationEpochMicros": 1,  # Deliberately useless as a timing source.
                                      "observedByEpochNanos": EPOCH_OFFSET + frame["submittedAtNanos"] + 10_000_000})
        clock = [{"hostBeforeEpochNanos": EPOCH_OFFSET + NATIVE, "guestEpochNanos": EPOCH_OFFSET + NATIVE,
                  "hostAfterEpochNanos": EPOCH_OFFSET + NATIVE + 100_000}]
        self.host_clock = self.root / "host-guest-clock.json"
        self.host_clock.write_text(json.dumps(clock), encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def write(self):
        (self.probe / "probe.json").write_text(json.dumps(self.metadata), encoding="utf-8")
        (self.probe / "frames.jsonl").write_text("".join(json.dumps(item) + "\n" for item in self.native), encoding="utf-8")
        (self.capture / "observations.jsonl").write_text("".join(json.dumps(item) + "\n" for item in self.observations), encoding="utf-8")
        (self.capture / "summary.json").write_text(json.dumps({"frames": len(self.observations), "missingSequences": 0}), encoding="utf-8")

    def analyze(self, host_clock=True):
        self.write()
        return analyze(self.capture, self.probe, None, self.host_clock if host_clock else None)

    def test_pixels_identify_native_scenes_but_never_establish_physical_presentation(self):
        result = self.analyze()
        self.assertEqual(result["uniqueNativeFramesObserved"], 4, result["observations"])
        self.assertEqual(result["nativeFramesNotUniquelyObserved"], 0)
        self.assertLess(result["submitToObservedUpperBoundMillis"]["maximum"], 11)
        self.assertFalse(result["qualifiesPhysicalPresentation"])
        self.assertTrue(result["requiresCalibration"])
        self.assertFalse(result["passed"])
        self.assertTrue(all(not item["estimatedTimestampUsedAsProof"] for item in result["observations"]))

    def test_rgb_hash_mismatch_fails_instead_of_matching_other_frames(self):
        (self.capture / self.observations[1]["file"]).write_bytes(b"wrong bytes")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.analyze()

    def test_declared_bottom_up_orientation_is_applied(self):
        image = read_rgb(self.capture, self.observations[0])
        np.testing.assert_array_equal(image[50, 50], self.source[81, 100])

    def test_explicit_orientation_model_preserves_recorded_declaration(self):
        for item in self.observations:
            path = self.capture / item["file"]
            read_rgb(self.capture, item).tofile(path)
            item["sha256"] = sha256(path)
        self.write()
        result = analyze(self.capture, self.probe, None, self.host_clock, "left-to-right,top-down")
        self.assertEqual(result["uniqueNativeFramesObserved"], 4)
        self.assertEqual(result["pixelOrderDeclaredByCapture"], "left-to-right,bottom-up")
        self.assertEqual(result["pixelOrderUsedForDiagnosticComparison"], "left-to-right,top-down")
        self.assertFalse(result["qualifiesPhysicalPresentation"])

    def test_repeated_source_scene_remains_ambiguous(self):
        duplicate = {**self.native[0], "token": 5, "bufferFrameId": 5,
                     "submittedAtNanos": NATIVE + 1_000_000, "sceneRevision": 2}
        self.native.append(duplicate)
        result = self.analyze()
        self.assertEqual(result["observations"][0]["candidateCount"], 2)
        self.assertEqual(result["uniqueNativeFramesObserved"], 3)

    def test_later_reobservation_does_not_replace_earliest_scene_receipt_bound(self):
        last = self.observations[-1]
        self.observations.append({**last, "sequence": 4, "observedByEpochNanos": last["observedByEpochNanos"] + 1_000_000_000})
        result = self.analyze()
        self.assertLess(result["submitToObservedUpperBoundMillis"]["maximum"], 11)
        self.assertGreater(result["observations"][-1]["submitToObservedUpperBoundMillis"], 1000)
        self.assertEqual(result["captureObservationCount"], 5)

    def test_screenshot_sequence_gap_is_not_a_native_frame_count(self):
        self.observations[2]["sequence"] = 3
        self.observations[3]["sequence"] = 4
        result = self.analyze()
        self.assertEqual(result["droppedScreenshotSequences"], 1)
        self.assertIn("capture", result["violationCounts"])
        self.assertFalse(result["canBoundEveryNativeFrameUnder100ms"])

    def test_no_host_clock_does_not_use_estimated_generation_time_as_fallback(self):
        result = self.analyze(host_clock=False)
        self.assertIsNone(result["submitToObservedUpperBoundMillis"]["maximum"])
        self.assertFalse(result["clockMapping"]["independentlyBracketed"])
        self.assertIn("clock", result["violationCounts"])

    def test_observation_receive_clock_before_native_frame_disallows_identity(self):
        for item in self.observations:
            item["observedByEpochNanos"] = EPOCH_OFFSET + NATIVE - 100_000_000
        self.assertEqual(self.analyze()["uniqueNativeFramesObserved"], 0)


if __name__ == "__main__":
    unittest.main()
