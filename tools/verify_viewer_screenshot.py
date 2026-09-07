"""Corroborate one fully visible source page against captured pixels; never grants corpus/display-time pass."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct

import numpy as np
from PIL import Image


class SnapshotReader:
    def __init__(self, data):
        self.stream = io.BytesIO(data)

    def read(self, count):
        value = self.stream.read(count)
        if len(value) != count:
            raise ValueError("Truncated complete-cache snapshot")
        return value

    def integer(self):
        return struct.unpack('>i', self.read(4))[0]

    def long(self):
        return struct.unpack('>q', self.read(8))[0]

    def flag(self):
        value = self.read(1)[0]
        if value not in (0, 1):
            raise ValueError("Invalid snapshot boolean")
        return bool(value)

    def text(self):
        size = struct.unpack('>H', self.read(2))[0]
        # DataOutputStream.writeUTF uses modified UTF-8 and UTF-16 surrogate code units.
        value = self.read(size).replace(b'\xc0\x80', b'\x00').decode('utf-8', 'surrogatepass')
        return value.encode('utf-16', 'surrogatepass').decode('utf-16')

    def optional(self):
        return self.text() if self.flag() else None

    def dimensions(self):
        return (self.integer(), self.integer()) if self.flag() else None


def read_snapshot(path):
    data = path.read_bytes()
    if len(data) > 4 * 1024 * 1024 + 44:
        raise ValueError("Oversized snapshot")
    envelope = SnapshotReader(data)
    if envelope.integer() != 0x4d565253 or envelope.integer() != 1:
        raise ValueError("Unknown snapshot format")
    size = envelope.integer()
    if not 0 < size <= 4 * 1024 * 1024:
        raise ValueError("Invalid payload size")
    expected = envelope.read(32)
    payload = envelope.read(size)
    if envelope.stream.read() or hashlib.sha256(payload).digest() != expected:
        raise ValueError("Snapshot checksum/trailing data mismatch")
    reader = SnapshotReader(payload)
    identity = dict(zip(('sourceId', 'seriesKey', 'episodeKey'), [reader.text() for _ in range(3)]))
    reader.text()  # Title does not establish page ownership.
    reader.optional(), reader.optional(), reader.optional()
    count = reader.integer()
    if not 0 < count <= 10000:
        raise ValueError("Invalid page count")
    pages = {}
    for ordinal in range(count):
        key = reader.text()
        declared_dimensions = reader.dimensions()
        declared_length = reader.long() if reader.flag() else None
        declared_hash = reader.optional()
        length, digest, dimensions = reader.long(), reader.text(), reader.dimensions()
        if (key in pages or not dimensions or min(dimensions) <= 0 or length <= 0
                or declared_dimensions not in (None, dimensions)
                or declared_length not in (None, length) or declared_hash not in (None, digest)):
            raise ValueError("Invalid/contradictory page binding")
        pages[key] = dict(identity, pageKey=key, ordinal=ordinal, dimensions=dimensions,
                          byteCount=length, sha256=digest)
    if reader.stream.read():
        raise ValueError("Trailing snapshot payload")
    return pages


def compare_nearest_bilinear(source, captured):
    """Exact rational pixel-center bilinear interpolation, allowing only nearest-integer ties."""
    height, width, _ = source.shape
    out_height, out_width, _ = captured.shape
    if width * out_height != height * out_width:
        raise ValueError("Screenshot region changes the original page aspect ratio")
    # Coordinates = (output_index + 1/2) * source_size / output_size - 1/2.
    xn = (2 * np.arange(out_width) + 1) * width - out_width
    yn = (2 * np.arange(out_height) + 1) * height - out_height
    xd, yd = 2 * out_width, 2 * out_height
    x0, y0 = xn // xd, yn // yd
    xf, yf = xn % xd, yn % yd
    x1, y1 = np.clip(x0 + 1, 0, width - 1), np.clip(y0 + 1, 0, height - 1)
    x0, y0 = np.clip(x0, 0, width - 1), np.clip(y0, 0, height - 1)
    samples, violations, maximum_error = 0, 0, 0.0
    denominator = xd * yd
    for start in range(0, out_height, 32):
        sl = slice(start, start + 32)
        fy = yf[sl, None, None]
        fx = xf[None, :, None]
        value = (source[y0[sl, None], x0[None, :]] * (yd - fy) * (xd - fx)
                 + source[y0[sl, None], x1[None, :]] * (yd - fy) * fx
                 + source[y1[sl, None], x0[None, :]] * fy * (xd - fx)
                 + source[y1[sl, None], x1[None, :]] * fy * fx)
        error = np.abs(captured[sl] * denominator - value)
        violations += int(np.count_nonzero(2 * error > denominator))
        maximum_error = max(maximum_error, float(error.max()) / denominator)
        samples += error.size
    return {'comparedRgbComponents': samples, 'componentsOutsideNearestIntegerEnvelope': violations,
            'maximumQuantizationError': maximum_error, 'sourceHeightRows': height,
            'sourceWidthPixels': width, 'observedSourceRowsConfirmed': violations == 0}


def verify(capture_path, snapshot_path, body_path, page_key):
    capture = json.loads(capture_path.read_text(encoding='utf-8-sig'))
    before, after = capture['before'], capture['after']
    start, end = capture['captureStartedAtNanos'], capture['captureCompletedAtNanos']
    if not (capture['captureStatus'] == 'CAPTURED' and 0 < before['snapshotCompletedAtNanos'] <= start <= end
            <= after['snapshotStartedAtNanos']):
        raise ValueError("Missing or noncausal capture brackets")
    for state in (before, after):
        if state['potentialOccluders'] or not state['hasWindowFocus']:
            raise ValueError("Occluded or unfocused reader cannot corroborate a complete page")
    if before['surface']['bounds'] != after['surface']['bounds']:
        raise ValueError("Viewport moved during capture")
    binding = read_snapshot(snapshot_path)[page_key]
    identity_fields = ('sourceId', 'seriesKey', 'episodeKey')
    for state in (before, after):
        if any(state['session'][key] != binding[key] for key in identity_fields):
            raise ValueError("Screenshot and source episode identity disagree")
    stable = ('pageKey', 'anchorOffsetUnits', 'scrollOffsetUnits', 'userInputRevision')
    if any(before['session'][key] != after['session'][key] for key in stable):
        raise ValueError("Session moved during capture")
    regions = []
    for state in (before, after):
        latest = state['candidateFrames'][0]
        if not latest['fullActualCoverage']:
            raise ValueError("Latest candidate has incomplete actual content")
        region = next(r for r in latest['regions'] if r['pageKey'] == page_key)
        if (not region['imageIdentityVerified'] or any(region[key] != binding[key] for key in identity_fields)
                or region['sourceTopRow'] != 0 or region['sourceBottomRowExclusive'] != binding['dimensions'][1]):
            raise ValueError("Candidate does not expose the complete bound page")
        regions.append(region)
    if regions[0] != regions[1]:
        raise ValueError("Page geometry changed during capture")
    body = body_path.read_bytes()
    if len(body) != binding['byteCount'] or hashlib.sha256(body).hexdigest() != binding['sha256']:
        raise ValueError("Original body disagrees with the normal-use verified snapshot")
    png = capture_path.with_name(capture['file'])
    if png.parent.resolve() != capture_path.parent.resolve() or hashlib.sha256(png.read_bytes()).hexdigest() != capture['pngSha256']:
        raise ValueError("Screenshot path/hash mismatch")
    original = Image.open(io.BytesIO(body))
    if original.size != binding['dimensions']:
        raise ValueError("Original image dimensions disagree with snapshot")
    screen = Image.open(png).convert('RGB')
    bounds, region = before['surface']['bounds'], regions[0]
    rectangle = (bounds['left'], bounds['top'] + region['screenTopPx'],
                 bounds['right'], bounds['top'] + region['screenBottomPx'])
    if not (0 <= rectangle[0] < rectangle[2] <= screen.width and 0 <= rectangle[1] < rectangle[3] <= screen.height):
        raise ValueError("Page region falls outside screenshot")
    result = compare_nearest_bilinear(np.asarray(original.convert('RGB'), dtype=np.int64),
                                      np.asarray(screen.crop(rectangle), dtype=np.int64))
    return dict(result, sourcePage={key: binding[key] for key in (*identity_fields, 'pageKey')},
                sourceBodySha256=binding['sha256'], pngSha256=capture['pngSha256'],
                snapshotSha256=hashlib.sha256(snapshot_path.read_bytes()).hexdigest(),
                captureIntervalNanos=[start, end], exactPhysicalPresentationTimeVerified=False,
                nativeBufferIdentityVerified=False, corpusCredit=0,
                provenance='Normal-use verified manifest/body plus independently captured screenshot pixels',
                scope='One complete page observed during capture; no claim about every displayed frame or absolute physical limits')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('capture', 'snapshot', 'body', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--page-key', required=True)
    args = parser.parse_args()
    try:
        result = verify(args.capture, args.snapshot, args.body, args.page_key)
    except Exception as problem:
        result = {'observedSourceRowsConfirmed': False, 'exactPhysicalPresentationTimeVerified': False,
                  'nativeBufferIdentityVerified': False, 'corpusCredit': 0, 'failure': str(problem)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result['observedSourceRowsConfirmed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
