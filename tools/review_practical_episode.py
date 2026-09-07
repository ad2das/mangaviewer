"""Review the revised practical corpus; never assert the former display-proof contract."""
import argparse
from fractions import Fraction
import hashlib
import json
from pathlib import Path

from collect_engine_live_trace import HEADER, verify_native_packet
from compare_engine_capture_pixels import compare
from compare_engine_stopped_screen import verify as verify_screens, visible_scene
from engine_raster_profile import load_profile
from engine_source_row_coverage import identity, fraction, merge, encoded
from engine_source_sampling import row_ranges, reference_identity
from verify_engine_episode_document import verify_plan
from verify_engine_episode_catalog import verify as verify_catalog
from verify_engine_http_exchanges import verify as verify_http, bind_plan
from verify_engine_ntk_document import load_authorization
from verify_engine_frame_observations import verify_rows as verify_frames
from verify_engine_input_observations import verify_rows as verify_inputs


def read(path):
    return json.loads(Path(path).read_bytes())


def require(value, reason):
    if not value:
        raise ValueError(reason)


def review(root, calibration, expected, archives=()):
    root = Path(root)
    artifacts = {}

    def save(name, value):
        path = root / (name + '.json')
        path.write_text(json.dumps(value, indent=2), encoding='utf-8')
        artifacts[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        return value

    collection = read(root / 'collection.json')
    require(collection.get('success') is True and collection.get('traceStopped') is True,
            'collector did not complete successfully')
    target = {key: expected[key] for key in ('sourceId', 'seriesKey', 'episodeKey')}
    require(collection['requestedEpisode'] == {**target, 'kind': expected['kind']},
            'collection differs from the fixed selected episode')
    require(collection.get('catalogUi') is True, 'real catalog UI entry is required')
    require(len(collection['captureDirectories']) == 1, 'ambiguous capture')
    capture = root / collection['captureDirectories'][0]
    profile = load_profile(calibration, collection)
    ledger = save('practical-http-history', verify_http(capture / 'http'))
    plans = [(path, read(path)) for path in (capture / 'episodes').glob('plan-*.json')]
    selected = [(path, plan) for path, plan in plans if plan['episodeIdentity'] == target]
    require(len(selected) == 1, 'selected episode has no unique source document')
    path, plan = selected[0]
    body = path.with_name(plan['documentFile']).read_bytes()
    authorization = load_authorization(capture / 'ntk-authorization', plan) if target['sourceId'] == 'ntk' else None
    document = save('practical-source-document', verify_plan(plan, body, authorization))
    catalog = save('practical-source-catalog', verify_catalog(plan, ledger, capture / 'http'))
    originals = root / 'original-sources'
    binding = save('practical-source-http-binding', bind_plan(
        plan, body, ledger, read(originals / 'manifest.json'), originals, authorization, archives))
    frames = save('practical-frame-history', verify_frames(
        [json.loads(line) for line in (capture / 'frames.jsonl').read_text().splitlines()],
        read(capture / 'renderer-close.json')))
    inputs = save('practical-input-history', verify_inputs(
        [json.loads(line) for line in (capture / 'inputs.jsonl').read_text().splitlines()],
        read(capture / 'input-close.json')))
    pages = {identity(page['pageIdentity']): {'pageIdentity': page['pageIdentity'],
             'height': None, 'rows': set(), 'intervals': []} for page in document['pages']}
    pixels = []
    for path in sorted(capture.glob('frame-*.json'), key=lambda p: int(p.stem.split('-')[1])):
        frame = read(path)
        ordinal = int(path.stem.split('-')[1])
        packet = (capture / f'native-{ordinal}.packet').read_bytes()
        raw = packet[HEADER.size:]
        verify_native_packet(frame, packet, raw)
        result = compare(frame, raw, originals, profile, include_source_sampling=True)
        result['frameFile'] = path.name
        pixels.append(result)
        if not result['capturedPixelsMatch']:
            continue
        for band in result['sourceBands']:
            key = identity(band['pageIdentity'])
            if key not in pages:
                continue
            candidates = [p for p in frame['placements'] if identity(p['pageIdentity']) == key
                          and p['sourceSha256'] == band['sourceSha256']
                          and p['screenTopUnits'] < band['capturedBottom'] * frame['coordinateUnitsPerPixel']
                          and p['screenBottomUnits'] > band['capturedTop'] * frame['coordinateUnitsPerPixel']]
            require(len(candidates) == 1, 'ambiguous source band placement')
            height = candidates[0]['sourceHeight']
            require(pages[key]['height'] in (None, height), 'source dimensions changed')
            pages[key]['height'] = height
            for start, end in band['sampledSourceRowRanges']:
                pages[key]['rows'].update(range(start, end))
            pages[key]['intervals'].append((fraction(band['sourceTopFraction']), fraction(band['sourceBottomFraction'])))
    save('practical-native-pixels', {'calibration': profile, 'frames': pixels,
         'physicalPresentationVerified': False, 'legacy200CorpusCredit': 0})
    coverage = []
    for page in pages.values():
        height = page['height']
        missing = row_ranges(set(range(height)) - page['rows']) if height else None
        cursor, gaps = Fraction(0), []
        for start, end in merge(page['intervals']):
            if cursor < start:
                gaps.append((cursor, start))
            cursor = end
        if height and cursor < height:
            gaps.append((cursor, Fraction(height)))
        coverage.append({'pageIdentity': page['pageIdentity'], 'sourceHeight': height,
                         'missingReferenceRowRanges': missing,
                         'continuousGeometricGaps': [encoded(span) for span in gaps]})
    save('practical-row-coverage', {'pages': coverage, 'sourceSamplingReference': reference_identity(),
         'physicalPresentationVerified': False, 'legacy200CorpusCredit': 0})
    screens = save('practical-stopped-pixels', verify_screens(capture, originals, require_same_submission=False))
    records = [read(path) for path in sorted(capture.glob('stopped-screen-*.json'))]
    require(len(records) == 2, 'two final compositor screens are required')
    summary = read(capture / 'summary.json')
    ownership = read(capture / 'ownership.json')
    gates = {
        'documentOrder': document['independentDocumentPageOrderVerified'],
        'episodeOrder': catalog['independentEpisodeCatalogOrderVerified'],
        'sourceHttpBytes': binding['sourceResponseBytesBindingVerified'],
        'documentEndpoints': summary['traversedDocumentEndpoints'],
        'nativePixels': bool(pixels) and all(p['capturedPixelsMatch'] for p in pixels),
        'discreteSourceRows': all(p['sourceHeight'] and p['missingReferenceRowRanges'] == [] for p in coverage),
        'finalCompositorPixels': screens['compositedScreenshotPixelsVerified'] and
                                all(screen['sourceBands'] for screen in screens['screens']),
        'identicalStoppedViewportPixels': len({screen['viewportPixelsSha256'] for screen in screens['screens']}) == 1,
        'stableStoppedScene': all(visible_scene(record[side]) == visible_scene(records[0]['before'])
                                 for record in records for side in ('before', 'after')),
        'stoppedInterval': records[1]['captureStartedMonotonicNs'] - records[0]['captureCompletedMonotonicNs'] >= 1_000_000_000,
        'finalFrameCaptured': summary['lastSubmittedFrameCaptured'],
        'inputHistory': inputs['completeSessionInputHistoryVerified'],
        'rendererHistory': frames['completeRendererHistoryVerified'],
        'noSwapFailure': frames['failedSwapCount'] == 0,
        'sampledMemory': read(capture / 'memory-policy.json')['sampledPssPolicyPassed'],
        'closedOwnership': all(ownership[key] == 0 for key in ('queued', 'active', 'retiring', 'subscribers',
                              'retainedResults', 'fileLeases', 'preparedPages', 'pendingPublications')),
        'decodeWorkersTerminated': ownership['decodeWorkersTerminated'],
    }
    return save('practical-review', {'scope': 'PRACTICAL_20_FUNCTIONAL_REVIEW',
        'practicalFunctionalPass': all(gates.values()), 'gates': gates, 'episodeIdentity': target,
        'appSha256': collection['app']['sha256'], 'testSha256': collection['test']['sha256'],
        'frameCount': len(pixels), 'expectedPages': len(pages), 'evidenceSha256': artifacts.copy(),
        'nativeSubmissionP95Millis': frames['nativeSubmissionP95Millis'],
        'nativeSubmissionMaxMillis': frames['nativeSubmissionMaxMillis'],
        'physicalPresentationVerified': False, 'legacy200CorpusCredit': 0,
        'limitations': ['Native pixel and discrete reference-row coverage, not continuous geometric or physical display proof.',
                        'Performance includes readback and memory instrumentation; no absolute performance acceptance claim.',
                        'Memory is sampled; continuous peak, all GL allocations and exact cache equivalence remain unproven.']})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--calibration', required=True, type=Path)
    parser.add_argument('--entry', required=True, type=Path)
    parser.add_argument('--archived-http-directory', action='append', type=Path, default=[])
    args = parser.parse_args()
    try:
        result = review(args.directory, args.calibration, read(args.entry), args.archived_http_directory)
    except Exception as error:
        result = {'practicalFunctionalPass': False, 'error': str(error), 'legacy200CorpusCredit': 0}
        (args.directory / 'practical-review-error.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        (args.directory / 'practical-review.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))
    raise SystemExit(0 if result['practicalFunctionalPass'] else 1)
