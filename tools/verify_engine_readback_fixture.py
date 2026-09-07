"""Verify the detached exact-pixel engine readback fixture."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import struct
import sys
from pathlib import Path
from typing import Any

from PIL import Image


MAGIC = 0x4552474253545250
VERSION = 1
STATUS_OK = 1
WIDTH = 64
HEIGHT = 96
STRIP_TOP = 8
STRIP_BOTTOM = 56
STRIP_ROWS = STRIP_BOTTOM - STRIP_TOP
PAYLOAD_BYTES = WIDTH * STRIP_ROWS * 4
HEADER = struct.Struct("<16q")
MAX_FILE_BYTES = 1 << 20
EXPECTED_FRAMES = tuple(range(1, 9))
EXPECTED_SOURCES = {"source-v0.png", "source-v1.png"}
HEX_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")


def _fail(reason: str) -> None:
    raise ValueError(reason)


def _require(condition: bool, reason: str) -> None:
    if not condition:
        _fail(reason)


def _int(value: Any, label: str) -> int:
    _require(isinstance(value, int) and not isinstance(value, bool), f"{label} must be an integer")
    return value


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _sha256_file(path: Path) -> tuple[bytes, str]:
    try:
        size = path.stat().st_size
    except OSError as error:
        _fail(f"cannot stat {path.name}: {error}")
    _require(size <= MAX_FILE_BYTES, f"{path.name} exceeds the 1 MiB file limit")
    try:
        data = path.read_bytes()
    except OSError as error:
        _fail(f"cannot read {path.name}: {error}")
    _require(len(data) == size, f"{path.name} changed while it was read")
    return data, _sha256_bytes(data)


def _hash_field(value: Any, label: str) -> str:
    _require(isinstance(value, str) and HEX_SHA256.fullmatch(value) is not None,
             f"{label} is not a SHA-256 hex string")
    return value.lower()


def _load_json(path: Path) -> dict[str, Any]:
    data, _ = _sha256_file(path)
    try:
        document = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        _fail(f"manifest is not valid UTF-8 JSON: {error}")
    _require(isinstance(document, dict), "manifest root must be an object")
    return document


def _contained_file(root: Path, relative: Any, label: str, used: set[Path]) -> Path:
    _require(isinstance(relative, str) and relative != "", f"{label} path must be a non-empty string")
    _require("\x00" not in relative, f"{label} path contains a NUL")
    raw_path = Path(relative)
    _require(not raw_path.is_absolute(), f"{label} path must be relative")
    candidate = (root / raw_path).resolve()
    try:
        candidate.relative_to(root)
    except ValueError:
        _fail(f"{label} path escapes the fixture directory: {relative}")
    _require(candidate not in used, f"duplicate fixture path: {relative}")
    used.add(candidate)
    _require(candidate.is_file(), f"{label} file is missing: {relative}")
    _require(not candidate.is_symlink(), f"{label} file must not be a symlink: {relative}")
    return candidate


def _expected_pixel(x: int, y: int, version: int) -> tuple[int, int, int, int]:
    return (
        (x * 3 + y + version * 41) & 0xFF,
        (y * 5 + x * 7 + version * 53) & 0xFF,
        (x ^ y ^ (version * 87)) & 0xFF,
        0xFF,
    )


def _verify_source(path: Path, version: int) -> bytes:
    source, _ = _sha256_file(path)
    try:
        with Image.open(io.BytesIO(source)) as image:
            _require(image.format == "PNG", f"{path.name} is not a PNG")
            _require(image.size == (WIDTH, HEIGHT),
                     f"{path.name} dimensions are {image.size}, expected {WIDTH}x{HEIGHT}")
            pixels = image.convert("RGBA").tobytes()
    except ValueError:
        raise
    except Exception as error:
        _fail(f"cannot decode {path.name} as a bounded PNG: {error}")
    _require(len(pixels) == WIDTH * HEIGHT * 4, f"{path.name} decoded to an unexpected byte count")
    for y in range(HEIGHT):
        for x in range(WIDTH):
            offset = (y * WIDTH + x) * 4
            expected = _expected_pixel(x, y, version)
            actual = tuple(pixels[offset:offset + 4])
            if actual != expected:
                _fail(
                    f"{path.name} pixel mismatch at x={x}, y={y}: "
                    f"expected {expected}, got {actual}"
                )
    return pixels


def _manifest_status(value: Any, label: str) -> int:
    if isinstance(value, str):
        _require(value == "OK", f"{label} must be OK")
        return STATUS_OK
    return _int(value, label)


def _verify_frame(
    root: Path,
    frame: dict[str, Any],
    expected_token: int,
    sources: dict[int, bytes],
    used_paths: set[Path],
    manifest_session: int,
    manifest_renderer_epoch: int,
) -> None:
    _require(isinstance(frame, dict), f"frame {expected_token} must be an object")
    token = _int(frame.get("token"), f"frame {expected_token} token")
    _require(token == expected_token, f"frame token {token} is not in the expected order")
    raw_path = _contained_file(root, frame.get("rawPacket"), f"frame {token}", used_paths)
    raw, raw_hash = _sha256_file(raw_path)
    _require(raw_hash == _hash_field(frame.get("sha256"), f"frame {token} sha256"),
             f"frame {token} raw packet hash does not match its manifest")
    _require(len(raw) >= HEADER.size, f"frame {token} raw packet is truncated")
    try:
        values = HEADER.unpack(raw[:HEADER.size])
    except struct.error as error:
        _fail(f"frame {token} header cannot be decoded: {error}")
    (
        magic, version, status, session, renderer_epoch, surface_epoch, packet_token,
        egl_frame_id, width, top, bottom, issued, ready, swap, payload_bytes, physical_flag,
    ) = values
    _require(magic == MAGIC, f"frame {token} has invalid packet magic")
    _require(version == VERSION, f"frame {token} has unsupported packet version {version}")
    _require(status == STATUS_OK, f"frame {token} has non-success packet status {status}")
    _require(session > 0 and renderer_epoch > 0 and surface_epoch > 0 and packet_token > 0,
             f"frame {token} has non-positive packet identity")
    _require(packet_token == token, f"frame {token} packet token is {packet_token}")
    _require(session == manifest_session, f"frame {token} session does not match the manifest")
    _require(renderer_epoch == manifest_renderer_epoch == 1,
             f"frame {token} renderer epoch is not 1")
    expected_epoch = 1 if token <= 4 else 2
    _require(surface_epoch == expected_epoch,
             f"frame {token} surface epoch is {surface_epoch}, expected {expected_epoch}")
    _require(width == WIDTH and top == STRIP_TOP and bottom == STRIP_BOTTOM,
             f"frame {token} geometry is {width}x{top}:{bottom}, expected 64x8:56")
    _require(egl_frame_id > 0, f"frame {token} EGL frame id is not positive")
    _require(issued > 0 and ready >= swap >= issued,
             f"frame {token} timestamps are not issued>0 and ready>=swap>=issued")
    _require(payload_bytes == PAYLOAD_BYTES, f"frame {token} payload byte count is {payload_bytes}")
    _require(physical_flag == 0, f"frame {token} claims physical presentation proof")
    _require(len(raw) == HEADER.size + PAYLOAD_BYTES,
             f"frame {token} raw packet length is {len(raw)}, expected {HEADER.size + PAYLOAD_BYTES}")

    _require(_manifest_status(frame.get("status"), f"frame {token} status") == status,
             f"frame {token} status does not match its packet")
    for name, expected in (
        ("sessionId", session),
        ("rendererEpoch", renderer_epoch),
        ("surfaceEpoch", surface_epoch),
        ("eglFrameId", egl_frame_id),
        ("width", width),
        ("top", top),
        ("bottom", bottom),
    ):
        _require(_int(frame.get(name), f"frame {token} {name}") == expected,
                 f"frame {token} {name} does not match its packet")
    _require(frame.get("physicalPresentationVerified") is False,
             f"frame {token} manifest physical proof must be false")

    payload = raw[HEADER.size:]
    version_index = (token - 1) % 2
    source = sources[version_index]
    for row in range(STRIP_ROWS):
        source_offset = ((STRIP_TOP + row) * WIDTH) * 4
        payload_offset = row * WIDTH * 4
        source_row = source[source_offset:source_offset + WIDTH * 4]
        actual_row = payload[payload_offset:payload_offset + WIDTH * 4]
        if source_row != actual_row:
            for x in range(WIDTH):
                pixel_offset = x * 4
                if source_row[pixel_offset:pixel_offset + 4] != actual_row[pixel_offset:pixel_offset + 4]:
                    _fail(f"frame {token} strip pixel mismatch at x={x}, y={STRIP_TOP + row}")
            _fail(f"frame {token} strip row mismatch at y={STRIP_TOP + row}")


def verify_fixture(directory: Path | str, expected_manifest_sha256: str) -> dict[str, Any]:
    """Verify one detached engine readback fixture and return its bounded report."""
    root = Path(directory).resolve()
    _require(root.is_dir(), f"fixture directory does not exist: {directory}")
    detached = _hash_field(expected_manifest_sha256, "detached manifest sha256")
    manifest_path = _contained_file(root, "manifest.json", "manifest", set())
    manifest_bytes, manifest_hash = _sha256_file(manifest_path)
    _require(manifest_hash == detached, "detached manifest sha256 does not match manifest.json")
    try:
        manifest = json.loads(manifest_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        _fail(f"manifest is not valid UTF-8 JSON: {error}")
    _require(isinstance(manifest, dict), "manifest root must be an object")
    _require(manifest.get("status") == "FIXTURE_REGRESSION_NO_CORPUS_CREDIT",
             "manifest status is not the fixture-only status")
    manifest_session = _int(manifest.get("sessionId"), "manifest sessionId")
    _require(manifest_session > 0, "manifest sessionId must be positive")
    manifest_renderer_epoch = _int(manifest.get("rendererEpoch"), "manifest rendererEpoch")
    _require(manifest_renderer_epoch == 1, "manifest rendererEpoch must be 1")
    _require(manifest.get("surfaceEpochs") == [1, 2], "manifest surfaceEpochs must be [1, 2]")
    _require(_int(manifest.get("surfaceWidth"), "manifest surfaceWidth") == WIDTH,
             "manifest surfaceWidth must be 64")
    _require(_int(manifest.get("surfaceHeight"), "manifest surfaceHeight") == HEIGHT,
             "manifest surfaceHeight must be 96")
    _require(_int(manifest.get("stripTop"), "manifest stripTop") == STRIP_TOP,
             "manifest stripTop must be 8")
    _require(_int(manifest.get("stripBottom"), "manifest stripBottom") == STRIP_BOTTOM,
             "manifest stripBottom must be 56")

    used_paths: set[Path] = set()
    source_records = manifest.get("sourcePng")
    _require(isinstance(source_records, list) and len(source_records) == 2,
             "manifest sourcePng must contain exactly two files")
    source_paths: dict[int, Path] = {}
    source_hashes: dict[int, str] = {}
    for record in source_records:
        _require(isinstance(record, dict), "sourcePng entries must be objects")
        name = record.get("name")
        _require(name in EXPECTED_SOURCES, f"unexpected source PNG name: {name}")
        version = 0 if name == "source-v0.png" else 1
        _require(version not in source_paths, f"duplicate source PNG: {name}")
        path = _contained_file(root, name, f"source version {version}", used_paths)
        data, digest = _sha256_file(path)
        expected_digest = _hash_field(record.get("sha256"), f"source {name} sha256")
        _require(digest == expected_digest, f"source {name} hash does not match its manifest")
        source_paths[version] = path
        source_hashes[version] = digest
        # Keep the bytes bounded and decode them exactly once below.
        _require(len(data) <= MAX_FILE_BYTES, f"source {name} exceeds the 1 MiB file limit")
    _require(set(source_paths) == {0, 1}, "manifest sourcePng must name source-v0.png and source-v1.png")
    sources = {version: _verify_source(path, version) for version, path in source_paths.items()}

    frames = manifest.get("frames")
    _require(isinstance(frames, list) and len(frames) == len(EXPECTED_FRAMES),
             "manifest frames must contain exactly eight entries")
    by_token: dict[int, dict[str, Any]] = {}
    for frame in frames:
        _require(isinstance(frame, dict), "manifest frame entries must be objects")
        token = _int(frame.get("token"), "manifest frame token")
        _require(token in EXPECTED_FRAMES, f"unexpected frame token {token}")
        _require(token not in by_token, f"duplicate frame token {token}")
        by_token[token] = frame
    _require(set(by_token) == set(EXPECTED_FRAMES), "manifest frame tokens are not exactly 1 through 8")
    for token in EXPECTED_FRAMES:
        _verify_frame(root, by_token[token], token, sources, used_paths,
                      manifest_session, manifest_renderer_epoch)

    return {
        "fixturePixelIdentityVerified": True,
        "frameCount": len(EXPECTED_FRAMES),
        "verifiedRows": len(EXPECTED_FRAMES) * STRIP_ROWS,
        "surfaceEpochs": [1, 2],
        "corpusCredit": 0,
        "producerLayerBindingVerified": False,
        "physicalPresentationVerified": False,
        "physicalPresentationTimeNanos": None,
        "surfaceFlingerOwnershipProof": False,
        "note": "No SurfaceFlinger ownership proof is provided by this host fixture validator.",
    }


def _failure_report(reason: str) -> dict[str, Any]:
    return {
        "fixturePixelIdentityVerified": False,
        "frameCount": 0,
        "verifiedRows": 0,
        "surfaceEpochs": [],
        "corpusCredit": 0,
        "producerLayerBindingVerified": False,
        "physicalPresentationVerified": False,
        "physicalPresentationTimeNanos": None,
        "surfaceFlingerOwnershipProof": False,
        "error": reason,
    }


def _write_report(path: Path, report: dict[str, Any]) -> None:
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except OSError as error:
        raise ValueError(f"cannot write validator report {path}: {error}") from error


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args(argv)
    try:
        report = verify_fixture(arguments.directory, arguments.manifest_sha256)
    except (OSError, ValueError) as error:
        report = _failure_report(str(error))
        try:
            _write_report(arguments.output, report)
        except ValueError as write_error:
            print(str(write_error), file=sys.stderr)
            return 1
        print(str(error), file=sys.stderr)
        return 1
    try:
        _write_report(arguments.output, report)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 1
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
