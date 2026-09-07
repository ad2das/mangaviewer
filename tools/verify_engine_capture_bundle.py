"""Run the normal-viewer evidence gates together; missing gates never receive corpus credit."""
import argparse
import hashlib
import json
from pathlib import Path
import re

from compare_engine_capture_pixels import verify as verify_pixels
from engine_source_row_coverage import require
from export_engine_capture_sources import export
from verify_engine_episode_document import verify_plan
from verify_engine_episode_inventory import inventory
from verify_engine_frame_observations import verify_rows as verify_frames
from verify_engine_input_observations import verify_rows as verify_inputs
from verify_engine_live_surface import verify as verify_surface
from compare_engine_stopped_screen import verify as verify_stopped_screens
from verify_engine_http_exchanges import verify as verify_http, bind_plan
from verify_engine_episode_catalog import verify as verify_catalog


def stage_verdict(name, value):
    exact = {'http-history': 'httpObservationHistoryVerified',
             'frame-history': 'completeRendererHistoryVerified',
             'input-history': 'completeSessionInputHistoryVerified',
             'source-export': 'success', 'surface': 'producerLayerBindingVerified',
             'stopped-screen-pixels': 'compositedScreenshotPixelsVerified',
             'stopped-screen-verification': 'finalStopVerified',
             'pixels-verification': 'independentCapturedPixelsVerified'}
    prefixes = {'document-': 'independentDocumentPageOrderVerified',
                'catalog-': 'independentEpisodeCatalogOrderVerified',
                'http-plan-binding-': 'sourceResponseBytesBindingVerified',
                'episode-inventory-': 'allEpisodeSourceRowsObserved'}
    field = exact.get(name) or next((field for prefix, field in prefixes.items() if name.startswith(prefix)), None)
    require(field is not None, 'diagnostic stage lacks an explicit result contract')
    passed = isinstance(value, dict) and value.get(field) is True
    return {'completed': passed, 'requiredResultField': field,
            **({} if passed else {'error': f'{field} is not verified'})}


def verify(root, adb=None, raster_calibration=None, archived_http_directories=()):
    root = Path(root).resolve()
    stages = {}
    artifacts = {}

    def stage(name, action):
        try:
            value = action()
            stages[name] = stage_verdict(name, value)
        except (OSError, ValueError, KeyError, TypeError) as failure:
            value = {'error': str(failure), 'corpusCredit': 0}
            stages[name] = {'completed': False, 'error': str(failure)}
        path = root / (name + '.json')
        path.write_text(json.dumps(value, indent=2), encoding='utf-8')
        artifacts[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        return value

    collection = json.loads((root / 'collection.json').read_bytes())
    require(collection.get('success') is True and collection.get('traceStopped') is True, 'collection did not complete')
    names = collection['captureDirectories']
    require(len(names) == 1 and re.fullmatch(r'engine-capture-[0-9]+', names[0]), 'invalid capture directory')
    capture = root / names[0]
    http = stage('http-history', lambda: verify_http(capture / 'http'))

    def journal(filename, proofname, verifier):
        raw, proof = (capture / filename).read_bytes(), (capture / proofname).read_bytes()
        result = verifier([json.loads(line) for line in raw.decode().splitlines()], json.loads(proof))
        result.update(journalSha256=hashlib.sha256(raw).hexdigest(), closeProofSha256=hashlib.sha256(proof).hexdigest())
        return result

    stage('frame-history', lambda: journal('frames.jsonl', 'renderer-close.json', verify_frames))
    stage('input-history', lambda: journal('inputs.jsonl', 'input-close.json', verify_inputs))
    plans = []
    for name in json.loads((capture / 'episodes' / 'index.json').read_bytes())['plans']:
        require(re.fullmatch(r'plan-[0-9]+\.json', name), 'invalid plan filename')
        path = capture / 'episodes' / name
        raw = path.read_bytes()
        plan = json.loads(raw)
        require(re.fullmatch(r'document-[0-9]+\.html', plan['documentFile']), 'invalid document filename')
        body = path.with_name(plan['documentFile']).read_bytes()
        authorization = None
        if plan['episodeIdentity']['sourceId'] == 'ntk':
            from verify_engine_ntk_document import load_authorization
            # Missing proof is recorded as a failing document stage, rather than
            # aborting the remaining independent evidence exports.
            try:
                authorization = load_authorization(capture / 'ntk-authorization', plan)
            except (OSError, ValueError, KeyError, TypeError):
                authorization = None
        stage('document-' + path.stem, lambda: {**verify_plan(plan, body, authorization), 'planSha256': hashlib.sha256(raw).hexdigest()})
        plans.append((plan, body, authorization))
        stage('catalog-' + path.stem, lambda: verify_catalog(plan, http, capture / 'http'))
    if adb and not (root / 'original-sources' / 'manifest.json').exists():
        stage('source-export', lambda: export(adb, root))
    for number, (plan, body, authorization) in enumerate(plans):
        stage(f'http-plan-binding-{number}', lambda: bind_plan(plan, body, http,
              json.loads((root / 'original-sources' / 'manifest.json').read_bytes()), root / 'original-sources',
              authorization, archived_http_directories))
    if list(capture.glob('stopped-screen-*.json')):
        stage('stopped-screen-pixels', lambda: verify_stopped_screens(capture, root / 'original-sources'))
    surface = stage('surface', lambda: verify_surface(root))
    if surface.get('producerLayerBindingVerified') is True:
        if list(capture.glob('stopped-screen-*.json')):
            from verify_engine_stopped_screen import verify as verify_stopped_interval
            stage('stopped-screen-verification', lambda: verify_stopped_interval(root))
        pixels = stage('pixels-verification', lambda: verify_pixels(root, raster_calibration))
        if pixels.get('independentCapturedPixelsVerified') is True:
            source_path = root / 'original-sources' / 'manifest.json'
            sources = json.loads(source_path.read_bytes())
            placements = [p for file in capture.glob('frame-*.json') for p in json.loads(file.read_bytes())['placements']]
            for number, (plan, body, authorization) in enumerate(plans):
                stage(f'episode-inventory-{number}', lambda: inventory(plan, body, placements, sources, source_path.parent, pixels, authorization))
    report = {'stages': stages, 'artifactSha256': artifacts, 'wholeEpisodeVerified': False, 'corpusCredit': 0,
              'scope': 'Diagnostic stage aggregation only. Full input/display timing, performance, memory and fixed-sample corpus qualification are not decided here.'}
    (root / 'bundle.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--adb')
    parser.add_argument('--raster-calibration', type=Path)
    parser.add_argument('--archived-http-directory', type=Path, action='append', default=[],
                        help='Revalidate a separate sealed HTTP ledger for cached source bytes only')
    args = parser.parse_args()
    result = verify(args.directory, args.adb, args.raster_calibration, args.archived_http_directory)
    print(json.dumps(result))
    raise SystemExit(0 if all(s['completed'] for s in result['stages'].values()) else 1)
