"""Negative controls for screenshot corroboration; no physical-display or corpus claims."""

import copy
import hashlib
import io
import json
import struct
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

from verify_viewer_screenshot import verify


def _write_utf(stream, value):
    encoded = value.encode("utf-8")
    stream.write(struct.pack(">H", len(encoded)))
    stream.write(encoded)


def _write_snapshot(path, body, *, source_id="ntk", series="series", episode="episode", page="p0000"):
    digest = hashlib.sha256(body).hexdigest()
    payload = io.BytesIO()
    for value in (source_id, series, episode, "fixture"):
        _write_utf(payload, value)
    payload.write(b"\x00\x00\x00")
    payload.write(struct.pack(">i", 1))
    _write_utf(payload, page)
    payload.write(b"\x01")
    payload.write(struct.pack(">ii", 2, 2))
    payload.write(b"\x01")
    payload.write(struct.pack(">q", len(body)))
    payload.write(b"\x01")
    _write_utf(payload, digest)
    payload.write(struct.pack(">q", len(body)))
    _write_utf(payload, digest)
    payload.write(b"\x01")
    payload.write(struct.pack(">ii", 2, 2))
    data = payload.getvalue()
    path.write_bytes(struct.pack(">ii", 0x4D565253, 1) + struct.pack(">i", len(data))
                       + hashlib.sha256(data).digest() + data)


class ViewerScreenshotVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.capture = self.root / "capture.json"
        self.snapshot = self.root / "complete-resume.snapshot"
        self.body = self.root / "p0000-original.page"
        self.png = self.root / "screen.png"
        self.output = self.root / "result.json"

        pixels = np.asarray([[[10, 20, 30], [40, 50, 60]],
                             [[70, 80, 90], [100, 110, 120]]], dtype=np.uint8)
        image = Image.fromarray(pixels, mode="RGB")
        image.save(self.body, format="PNG")
        self.png.write_bytes(self.body.read_bytes())
        _write_snapshot(self.snapshot, self.body.read_bytes())
        state = {
            "snapshotStartedAtNanos": 1000,
            "snapshotCompletedAtNanos": 1100,
            "activityIdentity": 1,
            "hasWindowFocus": True,
            "surface": {"bounds": {"left": 0, "top": 0, "right": 2, "bottom": 2}},
            "potentialOccluders": [],
            "session": {
                "sourceId": "ntk", "seriesKey": "series", "episodeKey": "episode", "pageKey": "p0000",
                "anchorOffsetUnits": 0, "scrollOffsetUnits": 0, "userInputRevision": 1,
            },
            "candidateFrames": [{
                "fullActualCoverage": True,
                "regions": [{
                    "sourceId": "ntk", "seriesKey": "series", "episodeKey": "episode", "pageKey": "p0000",
                    "sourceTopRow": 0, "sourceBottomRowExclusive": 2, "sourceHeightRows": 2,
                    "screenTopPx": 0, "screenBottomPx": 2, "viewportWidthPx": 2, "viewportHeightPx": 2,
                    "imageIdentityVerified": True,
                }],
            }],
        }
        capture = {
            "schemaVersion": 1,
            "captureStatus": "CAPTURED",
            "captureStartedAtNanos": 1200,
            "captureCompletedAtNanos": 1300,
            "file": self.png.name,
            "pngSha256": hashlib.sha256(self.png.read_bytes()).hexdigest(),
            "before": copy.deepcopy(state),
            "after": copy.deepcopy(state),
        }
        capture["before"]["snapshotCompletedAtNanos"] = 1100
        capture["after"]["snapshotStartedAtNanos"] = 1400
        self.capture.write_text(json.dumps(capture), encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def read_capture(self):
        return json.loads(self.capture.read_text(encoding="utf-8"))

    def write_capture(self, value):
        self.capture.write_text(json.dumps(value), encoding="utf-8")

    def run_verify(self):
        return verify(self.capture, self.snapshot, self.body, "p0000")

    def test_valid_capture_confirms_pixels_without_physical_or_corpus_credit(self):
        result = self.run_verify()
        self.assertTrue(result["observedSourceRowsConfirmed"])
        self.assertEqual(result["comparedRgbComponents"], 12)
        self.assertEqual(result["componentsOutsideNearestIntegerEnvelope"], 0)
        self.assertFalse(result["exactPhysicalPresentationTimeVerified"])
        self.assertFalse(result["nativeBufferIdentityVerified"])
        self.assertEqual(result["corpusCredit"], 0)

    def test_wrong_image_fails_after_image_hash_is_updated(self):
        image = Image.open(self.png).convert("RGB")
        image.putpixel((0, 0), (255, 0, 0))
        image.save(self.png, format="PNG")
        capture = self.read_capture()
        capture["pngSha256"] = hashlib.sha256(self.png.read_bytes()).hexdigest()
        self.write_capture(capture)
        result = self.run_verify()
        self.assertFalse(result["observedSourceRowsConfirmed"])
        self.assertGreater(result["componentsOutsideNearestIntegerEnvelope"], 0)

    def test_wrong_source_row_is_rejected_before_pixel_comparison(self):
        capture = self.read_capture()
        for state in (capture["before"], capture["after"]):
            state["candidateFrames"][0]["regions"][0]["sourceBottomRowExclusive"] = 1
        self.write_capture(capture)
        with self.assertRaisesRegex(ValueError, "complete bound page"):
            self.run_verify()

    def test_wrong_png_hash_is_rejected(self):
        capture = self.read_capture()
        capture["pngSha256"] = "0" * 64
        self.write_capture(capture)
        with self.assertRaisesRegex(ValueError, "Screenshot path/hash mismatch"):
            self.run_verify()

    def test_capture_occlusion_is_rejected(self):
        capture = self.read_capture()
        capture["before"]["potentialOccluders"] = [{"class": "Overlay"}]
        self.write_capture(capture)
        with self.assertRaisesRegex(ValueError, "Occluded or unfocused"):
            self.run_verify()

    def test_malformed_snapshot_checksum_is_rejected(self):
        data = bytearray(self.snapshot.read_bytes())
        data[-1] ^= 1
        self.snapshot.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "checksum/trailing data mismatch"):
            self.run_verify()


if __name__ == "__main__":
    unittest.main()
