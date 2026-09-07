import copy
import hashlib
import json
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

try:
    from verify_engine_readback_fixture import (
        HEADER,
        HEIGHT,
        MAGIC,
        STRIP_BOTTOM,
        STRIP_TOP,
        WIDTH,
        verify_fixture,
    )
except ImportError:
    from tools.verify_engine_readback_fixture import (
        HEADER,
        HEIGHT,
        MAGIC,
        STRIP_BOTTOM,
        STRIP_TOP,
        WIDTH,
        verify_fixture,
    )


def _png_chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload)) + kind + payload +
        struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    )


def _write_png(path: Path, rgba: bytes) -> None:
    rows = b"".join(b"\x00" + rgba[y * WIDTH * 4:(y + 1) * WIDTH * 4] for y in range(HEIGHT))
    header = struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 6, 0, 0, 0)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n" +
        _png_chunk(b"IHDR", header) +
        _png_chunk(b"IDAT", zlib.compress(rows, 9)) +
        _png_chunk(b"IEND", b""),
    )


def _pixel(x: int, y: int, version: int) -> tuple[int, int, int, int]:
    return (
        (x * 3 + y + version * 41) & 0xFF,
        (y * 5 + x * 7 + version * 53) & 0xFF,
        (x ^ y ^ (version * 87)) & 0xFF,
        255,
    )


def _source(version: int) -> bytes:
    return bytes(channel for y in range(HEIGHT) for x in range(WIDTH) for channel in _pixel(x, y, version))


class EngineReadbackFixtureVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.sources = {version: _source(version) for version in (0, 1)}
        for version, rgba in self.sources.items():
            _write_png(self.root / f"source-v{version}.png", rgba)
        for token in range(1, 9):
            self._write_packet(token)
        self.manifest = self._new_manifest()
        self._write_manifest()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write_packet(self, token: int, *, values: list[int] | None = None, payload: bytes | None = None) -> None:
        version = (token - 1) % 2
        if payload is None:
            source = self.sources[version]
            payload = b"".join(
                source[(y * WIDTH) * 4:(y + 1) * WIDTH * 4]
                for y in range(STRIP_TOP, STRIP_BOTTOM)
            )
        if values is None:
            values = [
                MAGIC, 1, 1, 777, 1, 1 if token <= 4 else 2, token,
                1000 + token, WIDTH, STRIP_TOP, STRIP_BOTTOM,
                10_000 + token, 20_000 + token, 15_000 + token,
                len(payload), 0,
            ]
        path = self.root / f"frame-{token}.rawpacket.bin"
        path.write_bytes(HEADER.pack(*values) + payload)

    def _new_manifest(self) -> dict:
        return {
            "status": "FIXTURE_REGRESSION_NO_CORPUS_CREDIT",
            "sessionId": 777,
            "rendererEpoch": 1,
            "surfaceEpochs": [1, 2],
            "surfaceWidth": WIDTH,
            "surfaceHeight": HEIGHT,
            "stripTop": STRIP_TOP,
            "stripBottom": STRIP_BOTTOM,
            "sourcePng": [
                {"name": "source-v0.png", "sha256": self._sha(self.root / "source-v0.png")},
                {"name": "source-v1.png", "sha256": self._sha(self.root / "source-v1.png")},
            ],
            "frames": [
                {
                    "token": token,
                    "rawPacket": f"frame-{token}.rawpacket.bin",
                    "sha256": self._sha(self.root / f"frame-{token}.rawpacket.bin"),
                    "status": "OK",
                    "sessionId": 777,
                    "rendererEpoch": 1,
                    "surfaceEpoch": 1 if token <= 4 else 2,
                    "eglFrameId": 1000 + token,
                    "width": WIDTH,
                    "top": STRIP_TOP,
                    "bottom": STRIP_BOTTOM,
                    "physicalPresentationVerified": False,
                }
                for token in range(1, 9)
            ],
        }

    def _sha(self, path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def _write_manifest(self) -> None:
        data = json.dumps(self.manifest, separators=(",", ":")).encode("utf-8")
        (self.root / "manifest.json").write_bytes(data)
        self.manifest_sha = hashlib.sha256(data).hexdigest()

    def _refresh_manifest_hashes(self) -> None:
        for source in self.manifest["sourcePng"]:
            path = self.root / source["name"]
            if path.is_file():
                source["sha256"] = self._sha(path)
        for frame in self.manifest["frames"]:
            path = self.root / frame["rawPacket"]
            if path.is_file():
                frame["sha256"] = self._sha(path)
        self._write_manifest()

    def _mutate_packet(self, token: int, mutate, *, update_manifest: bool = False) -> None:
        path = self.root / f"frame-{token}.rawpacket.bin"
        raw = path.read_bytes()
        values = list(HEADER.unpack(raw[:HEADER.size]))
        payload = bytearray(raw[HEADER.size:])
        mutate(values, payload)
        self._write_packet(token, values=values, payload=bytes(payload))
        if update_manifest:
            frame = next(frame for frame in self.manifest["frames"] if frame["token"] == token)
            frame["surfaceEpoch"] = values[5]
            frame["token"] = values[6]
        self._refresh_manifest_hashes()

    def _assertRejected(self, expected: str) -> None:
        with self.assertRaisesRegex(ValueError, expected):
            verify_fixture(self.root, self.manifest_sha)

    def test_good_fixture(self) -> None:
        report = verify_fixture(self.root, self.manifest_sha)
        self.assertTrue(report["fixturePixelIdentityVerified"])
        self.assertEqual(report["frameCount"], 8)
        self.assertEqual(report["verifiedRows"], 384)
        self.assertEqual(report["surfaceEpochs"], [1, 2])
        self.assertEqual(report["corpusCredit"], 0)
        self.assertFalse(report["producerLayerBindingVerified"])
        self.assertFalse(report["physicalPresentationVerified"])
        self.assertIsNone(report["physicalPresentationTimeNanos"])

    def test_stale_same_size_epoch_rejected(self) -> None:
        self._mutate_packet(5, lambda values, _: values.__setitem__(5, 1), update_manifest=True)
        self._assertRejected("surface epoch")

    def test_token_swap_rejected(self) -> None:
        self._mutate_packet(1, lambda values, _: values.__setitem__(6, 2))
        self._assertRejected("packet token")

    def test_corrupt_pixel_rejected_after_hash_update(self) -> None:
        def corrupt(_: list[int], payload: bytearray) -> None:
            payload[17] ^= 1

        self._mutate_packet(1, corrupt)
        self._assertRejected("strip pixel mismatch")

    def test_wrong_source_rejected_after_hash_update(self) -> None:
        rgba = bytearray(self.sources[0])
        rgba[0] ^= 1
        _write_png(self.root / "source-v0.png", bytes(rgba))
        self._refresh_manifest_hashes()
        self._assertRejected("source-v0.png pixel mismatch")

    def test_inverted_time_rejected(self) -> None:
        self._mutate_packet(1, lambda values, _: (values.__setitem__(12, 10), values.__setitem__(13, 20)))
        self._assertRejected("timestamps")

    def test_truncated_packet_rejected(self) -> None:
        path = self.root / "frame-1.rawpacket.bin"
        path.write_bytes(path.read_bytes()[:-1])
        self._refresh_manifest_hashes()
        self._assertRejected("raw packet length")

    def test_unknown_version_rejected(self) -> None:
        self._mutate_packet(1, lambda values, _: values.__setitem__(1, 2))
        self._assertRejected("unsupported packet version")

    def test_unknown_status_rejected(self) -> None:
        self._mutate_packet(1, lambda values, _: values.__setitem__(2, 9))
        self._assertRejected("non-success packet status")

    def test_physical_flag_rejected(self) -> None:
        self._mutate_packet(1, lambda values, _: values.__setitem__(15, 1))
        self._assertRejected("physical presentation proof")

    def test_missing_frame_rejected(self) -> None:
        self.manifest["frames"].pop()
        self._write_manifest()
        self._assertRejected("exactly eight")

    def test_duplicate_frame_rejected(self) -> None:
        self.manifest["frames"][1] = copy.deepcopy(self.manifest["frames"][0])
        self._write_manifest()
        self._assertRejected("duplicate frame token")

    def test_manifest_path_escape_rejected(self) -> None:
        self.manifest["frames"][0]["rawPacket"] = "../outside.rawpacket.bin"
        self._write_manifest()
        self._assertRejected("escapes the fixture directory")

    def test_detached_manifest_mismatch_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "detached manifest sha256"):
            verify_fixture(self.root, "0" * 64)


if __name__ == "__main__":
    unittest.main()
