"""Compare real compositor screenshots with original source pixels; SF temporal binding is separate."""
import argparse
import hashlib
import json
from pathlib import Path
import re

from PIL import Image

from compare_engine_capture_pixels import compare, require


def visible_scene(snapshot):
    """Retain geometry/content/input identity; omit submission bookkeeping only."""
    metadata = {'observedMonotonicNs', 'token', 'eglFrameId', 'timestampNanos',
                'submittedAtNanos', 'geometryRevision'}
    return {key: value for key, value in snapshot.items() if key not in metadata}


def compare_screen(record, png, originals, require_same_submission=True):
    require(record.get('kind') == 'UI_AUTOMATION_COMPOSITED_SCREENSHOT' and record.get('forcedScene') is False and
            record.get('nativeReadback') is False, 'invalid compositor screenshot provenance kind')
    before, after = record['before'], record['after']
    relevant = lambda snapshot: {k: v for k, v in snapshot.items() if k != 'observedMonotonicNs'}
    if not require_same_submission:
        relevant = visible_scene
    require(relevant(before) == relevant(after), 'reported scene changed during screenshot acquisition')
    clocks = [before['observedMonotonicNs'], record['captureStartedMonotonicNs'],
              record['captureCompletedMonotonicNs'], after['observedMonotonicNs']]
    require(all(type(v) is int and v > 0 for v in clocks) and clocks == sorted(clocks), 'invalid screenshot clock interval')
    require(after.get('swapSucceeded') is True, 'reported screenshot scene was not submitted successfully')
    x, y, width, height = [after[k] for k in ('surfaceLeft', 'surfaceTop', 'surfaceWidth', 'surfaceHeight')]
    require(all(type(v) is int for v in (x, y, width, height)) and min(x, y) >= 0 and width > 0 and height > 0,
            'invalid observed SurfaceView rectangle')
    require((width, height) == (after['width'], after['viewportHeight']), 'scene does not match observed SurfaceView size')
    with Image.open(png) as screen:
        require(screen.size == (record['screenWidth'], record['screenHeight']), 'compositor screenshot dimensions changed')
        require(x + width <= screen.width and y + height <= screen.height, 'SurfaceView rectangle is outside screenshot')
        raw = screen.convert('RGBA').crop((x, y, x + width, y + height)).tobytes()
    result = compare({**after, 'top': 0, 'bottom': height}, raw, originals)
    result.update(compositedScreenshotPixelsVerified=result['capturedPixelsMatch'],
                  nativeReadback=False, finalStopVerified=False, producerLayerBindingVerified=False,
                  screenshotSha256=hashlib.sha256(Path(png).read_bytes()).hexdigest(),
                  viewportPixelsSha256=hashlib.sha256(raw).hexdigest(),
                  requireSameSubmission=require_same_submission,
                  captureStartedMonotonicNs=clocks[1], captureCompletedMonotonicNs=clocks[2],
                  scope='Actual compositor screenshot crop from observed SurfaceView bounds; reported scene stayed fixed during acquisition. Exact SF buffer/stopped-interval binding remains required.')
    return result


def verify(capture, originals, require_same_submission=True):
    capture = Path(capture)
    records = []
    for path in sorted(capture.glob('stopped-screen-*.json')):
        raw = path.read_bytes()
        record = json.loads(raw)
        require(re.fullmatch(r'stopped-screen-[0-9]+\.png', record['imageFile']), 'invalid screenshot filename')
        result = compare_screen(record, capture / record['imageFile'], originals, require_same_submission)
        result['recordSha256'] = hashlib.sha256(raw).hexdigest()
        records.append(result)
    require(len(records) == 2, 'two final-stop screenshots are required')
    return {'screens': records, 'compositedScreenshotPixelsVerified': all(r['capturedPixelsMatch'] for r in records),
            'sameReportedTokenAcrossStop': records[0]['token'] == records[1]['token'],
            'finalStopVerified': False, 'physicalPresentationVerified': False, 'wholeEpisodeVerified': False, 'corpusCredit': 0}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture', type=Path, required=True)
    parser.add_argument('--originals', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        report = verify(args.capture, args.originals)
    except (OSError, ValueError, KeyError, TypeError) as failure:
        report = {'compositedScreenshotPixelsVerified': False, 'error': str(failure), 'corpusCredit': 0}
    args.output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))
    raise SystemExit(0 if report['compositedScreenshotPixelsVerified'] else 1)
