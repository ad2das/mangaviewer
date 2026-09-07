"""Verify the fixed device calibration before enabling its predictive edge reference."""
import hashlib
import json
from pathlib import Path

from engine_raster_edge_model import predicted_upper_row

MODEL = 'FP32_VIEWPORT_8BIT_NEAREST_EVEN_UPPER_EDGE_V1'


def require(value, message):
    if not value:
        raise ValueError(message)


def validate_cases(cases):
    expected = [row * 1024 + offset for row in (2, 65, 155, 1069, 1712, 2135)
                for offset in (508, 509, 510, 511, 512, 513, 514, 515, 516)]
    require([case['edgeUnits'] for case in cases] == expected, 'calibration grid is incomplete or changed')
    for case in cases:
        require((case['subpixelBits'], case['sampleBuffers'], case['samples']) == (8, 0, 0),
                'calibration is not the measured single-sample 8-bit rasterizer')
        center = case['edgeUnits'] // 1024
        require([row['row'] for row in case['rows']] == [center - 1, center, center + 1], 'calibration rows changed')
        for row in case['rows']:
            upper = predicted_upper_row(row['row'], case['edgeUnits'], 2138)
            require((row['upperPixels'], row['lowerPixels'], row['otherPixels']) ==
                    ((1080, 0, 0) if upper else (0, 1080, 0)), 'device calibration disagrees with frozen edge model')


def load_profile(directory, collection):
    root = Path(directory).resolve()
    record = json.loads((root / 'collection.json').read_bytes())
    require(record.get('success') is True and record.get('testCount', 0) >= 1, 'calibration did not pass')
    require(record['avd'] == collection['avd'] == 'MangaViewerApi35', 'calibration device differs')
    digests = {}
    for package, key, filename in [('ml.melun.mangaview', 'app', 'app.apk'),
                                    ('ml.melun.mangaview.test', 'test', 'test.apk')]:
        digest = hashlib.sha256((root / filename).read_bytes()).hexdigest()
        require(digest == record[package]['sha256'] == collection[key]['sha256'], 'calibration APK differs from capture')
        digests[filename] = digest
    raw = (root / 'capture/result.json').read_bytes()
    result = json.loads(raw)
    require(result['classification'] == 'SYNTHETIC_RASTER_EDGE_CONTROL', 'unsupported calibration kind')
    validate_cases(result['cases'])
    check = json.loads((root / 'prediction-check.json').read_bytes())
    frozen_path = Path(check['predictionSource']).resolve()
    frozen_bytes = frozen_path.read_bytes()
    model_sha = hashlib.sha256(Path(__file__).with_name('engine_raster_edge_model.py').read_bytes()).hexdigest()
    require(hashlib.sha256(frozen_bytes).hexdigest() == check['predictionSha256'] and
            json.loads(frozen_bytes)['modelSha256'] == model_sha, 'frozen calibration model changed')
    require(check['caseCount'] == check['matchedCaseCount'] == 54 and not check['failedEdgeUnits'],
            'calibration did not match the frozen predictions')
    for name in ('collection.json', 'capture/result.json', 'prediction-check.json', 'instrumentation.txt'):
        digests[name] = hashlib.sha256((root / name).read_bytes()).hexdigest()
    return {'model': MODEL, 'calibrationDirectory': str(root), 'calibrationFilesSha256': digests,
            'frozenPredictionSha256': check['predictionSha256'], 'modelSha256': model_sha}


def validate_frame(profile, frame):
    require(profile.get('model') == MODEL, 'unsupported rasterization reference profile')
    require((frame['width'], frame['viewportHeight'], frame['coordinateUnitsPerPixel']) == (1080, 2138, 1024),
            'viewport differs from device calibration')
    info = frame.get('rasterizationInfo')
    require(isinstance(info, dict) and all(type(info.get(k)) is int for k in ('subpixelBits', 'sampleBuffers', 'samples')) and
            (info['subpixelBits'], info['sampleBuffers'], info['samples']) == (8, 0, 0),
            'captured rasterization metadata does not match calibration')
