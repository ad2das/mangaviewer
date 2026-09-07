"""Independent original-image comparison of native captured strips; not whole-episode qualification."""
import argparse
from fractions import Fraction
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image

MAX_ROW_RGB_MAE = 4.0  # Existing capture RGB tolerance, applied per row rather than over the whole image.


def require(condition, message):
    if not condition:
        raise ValueError(message)


def compare(frame, raw, originals, raster_profile=None, include_source_sampling=False):
    width, top, bottom = (frame[key] for key in ('width', 'top', 'bottom'))
    units = frame['coordinateUnitsPerPixel']
    require(type(units) is int and units in (1, 1024), 'unsupported coordinate precision')
    require(width > 0 and 0 <= top < bottom <= frame['viewportHeight'], 'invalid strip bounds')
    require(len(raw) == width * (bottom - top) * 4, 'invalid pixel payload size')
    actual = np.frombuffer(raw, dtype=np.uint8).reshape(bottom - top, width, 4)
    expected = np.zeros(actual.shape, dtype=np.float64)
    expected[:, :, 3] = 255
    centers = np.arange(top, bottom, dtype=np.float64) + 0.5
    owners = np.full(bottom - top, -1, dtype=np.int64)
    if raster_profile is not None:
        from engine_raster_profile import validate_frame
        validate_frame(raster_profile, frame)
    for index, placement in enumerate(frame['placements']):
        digest = placement['sourceSha256']
        require(isinstance(digest, str) and len(digest) == 64 and all(c in '0123456789abcdef' for c in digest), 'invalid source digest')
        source = Path(originals) / (digest + '.page')
        require(hashlib.sha256(source.read_bytes()).hexdigest() == digest, 'source content digest mismatch')
        sw, sh = placement['sourceWidth'], placement['sourceHeight']
        require(sw > 0 and sh > 0 and 0 <= placement['sourceTop'] < placement['sourceBottom'] <= sh, 'invalid source geometry')
        rh = (sh * width + sw - 1) // sw
        rt = placement['sourceTop'] * rh // sh
        rb = (placement['sourceBottom'] * rh + sh - 1) // sh
        require((placement['displayWidth'], placement['rasterHeight'], placement['rasterTop'], placement['rasterBottom']) ==
                (width, rh, rt, rb), 'declared raster crop disagrees with independent geometry')
        with Image.open(source) as original:
            require(original.size == (sw, sh), 'decoded original dimensions mismatch')
            require(not original.info.get('icc_profile'), 'ICC source requires an explicit independent color transform')
            rgba = original.convert('RGBA')
            require(rgba.getchannel('A').getextrema() == (255, 255), 'transparent source requires premultiplied reference support')
            raster = np.asarray(rgba.resize((width, rh), Image.Resampling.BILINEAR).crop((0, rt, width, rb)), dtype=np.float64)
        qt, qb = placement['screenTopUnits'] / units, placement['screenBottomUnits'] / units
        require(qb > qt, 'inverted quad')
        if raster_profile is None:
            mask = (centers >= qt) & (centers < qb)
        else:
            from engine_raster_edge_model import window_edge
            raster_top = window_edge(placement['screenTopUnits'], frame['viewportHeight'])
            raster_bottom = window_edge(placement['screenBottomUnits'], frame['viewportHeight'])
            mask = (centers > raster_top) & (centers <= raster_bottom)
        texture_y = (centers[mask] - qt) * (rb - rt) / (qb - qt) - 0.5
        low = np.floor(texture_y).astype(np.int64)
        weight = (texture_y - low)[:, None, None]
        expected[mask] = raster[np.clip(low, 0, rb - rt - 1)] * (1 - weight) + raster[np.clip(low + 1, 0, rb - rt - 1)] * weight
        owners[mask] = index
    errors = np.abs(actual[:, :, :3].astype(np.float64) - expected[:, :, :3])
    row_errors = errors.mean(axis=(1, 2))
    passed = bool(np.all(row_errors <= MAX_ROW_RGB_MAE) and np.all(actual[:, :, 3] == 255))
    bands = []
    start = 0
    while start < len(owners):
        end = start + 1
        while end < len(owners) and owners[end] == owners[start]:
            end += 1
        owner = int(owners[start])
        if owner >= 0:
            p = frame['placements'][owner]
            qt, qb = Fraction(p['screenTopUnits'], units), Fraction(p['screenBottomUnits'], units)
            def source_y(y):
                raster_y = p['rasterTop'] + (Fraction(y) - qt) * (p['rasterBottom'] - p['rasterTop']) / (qb - qt)
                return max(Fraction(p['sourceTop']), min(Fraction(p['sourceBottom']), raster_y * p['sourceHeight'] / p['rasterHeight']))
            first, last = source_y(max(Fraction(top + start), qt)), source_y(min(Fraction(top + end), qb))
            band = {'pageIdentity': p['pageIdentity'], 'sourceSha256': p['sourceSha256'],
                'sourceTopFraction': [first.numerator, first.denominator], 'sourceBottomFraction': [last.numerator, last.denominator],
                'capturedTop': top + start, 'capturedBottom': top + end}
            if include_source_sampling:
                from engine_source_sampling import sampled_rows, row_ranges
                band['sampledSourceRowRanges'] = row_ranges(sampled_rows(p, units, top + start, top + end))
            bands.append(band)
        start = end
    return {'token': frame['token'], 'capturedPixelsMatch': passed, 'rgbMeanAbsoluteError': float(errors.mean()),
            'rgbMaxAbsoluteError': float(errors.max()), 'maxRowRgbMeanAbsoluteError': float(row_errors.max()),
            'uncoveredCapturedRows': int(np.sum(owners < 0)), 'sourceBands': bands,
            'physicalPresentationVerified': False, 'wholeEpisodeVerified': False, 'corpusCredit': 0}


def verify(directory, calibration_directory=None, include_source_sampling=False):
    root = Path(directory).resolve()
    surface = json.loads((root / 'surface.json').read_text())
    require(surface.get('producerLayerBindingVerified') is True and surface.get('nativePacketVerified') is True,
            'native packet and SF binding are required')
    require(hashlib.sha256((root / 'collection.json').read_bytes()).hexdigest() == surface['collectionSha256'], 'collection changed')
    for name, digest in surface['capturedFilesSha256'].items():
        path = (root / name).resolve()
        require(path.is_relative_to(root) and hashlib.sha256(path.read_bytes()).hexdigest() == digest, 'bound capture file changed')
    collection = json.loads((root / 'collection.json').read_text())
    profile = None
    if calibration_directory is not None:
        from engine_raster_profile import load_profile
        profile = load_profile(calibration_directory, collection)
    capture = root / collection['captureDirectories'][0]
    reports = []
    sampling = None
    if include_source_sampling:
        from engine_source_sampling import reference_identity
        sampling = reference_identity()
    for path in sorted(capture.glob('frame-*.json')):
        index = int(path.stem.split('-')[1])
        reports.append(compare(json.loads(path.read_text()), (capture / f'strip-{index}.rgba').read_bytes(),
                               root / 'original-sources', profile, include_source_sampling))
    require(len(reports) == surface['frameCount'] and bool(reports), 'capture count changed')
    return {'independentCapturedPixelsVerified': all(r['capturedPixelsMatch'] for r in reports),
        'comparisonToleranceMaxRowRgbMae': MAX_ROW_RGB_MAE,
        'surfaceReportSha256': hashlib.sha256((root / 'surface.json').read_bytes()).hexdigest(),
        'reference': 'Pillow original decode, full-raster bilinear resize/crop, then GL pixel-center interpolation',
            'rasterizationProfile': profile,
            'sourceSamplingReference': sampling,
            'frames': reports, 'physicalPresentationVerified': False, 'wholeEpisodeVerified': False, 'corpusCredit': 0}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--raster-calibration', type=Path)
    parser.add_argument('--include-source-sampling', action='store_true',
                        help='Also report discrete reference-filter row dependencies; does not replace the coverage gate')
    args = parser.parse_args()
    try:
        report = verify(args.directory, args.raster_calibration, args.include_source_sampling)
    except (OSError, ValueError, KeyError, TypeError) as failure:
        report = {'independentCapturedPixelsVerified': False, 'error': str(failure), 'corpusCredit': 0}
    args.output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))
    raise SystemExit(0 if report['independentCapturedPixelsVerified'] else 1)
