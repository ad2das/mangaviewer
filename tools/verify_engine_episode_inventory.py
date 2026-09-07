"""Measure whole-document row coverage against originals and independently verified capture pixels."""
import argparse
import hashlib
import json
from pathlib import Path
import re

from PIL import Image

from compare_engine_capture_pixels import verify as verify_pixels
from engine_cache_identity import cache_name
from engine_source_row_coverage import coverage, identity, require
from verify_engine_episode_document import verify_plan


def inventory(plan, body, placements, sources, originals, pixel_report, ntk_authorization=None):
    document = verify_plan(plan, body, ntk_authorization)
    expected = {identity(p['pageIdentity']): p for p in document['pages']}
    require(sources.get('success') is True and sources.get('readOnlyCacheExport') is True and
            sources.get('networkRequests') == 0, 'original cache export did not complete')
    bindings = {}
    for source in sources['sources']:
        digest = source['sha256']
        require(re.fullmatch(r'[0-9a-f]{64}', digest) is not None and source['file'] == digest + '.page',
                'invalid original filename/digest')
        path = Path(originals) / source['file']
        with path.open('rb') as stream:
            require(hashlib.file_digest(stream, 'sha256').hexdigest() == digest, 'original bytes changed')
        with Image.open(path) as image:
            dimensions = image.size
        for binding in source['cacheBindings']:
            key = identity(binding['pageIdentity'])
            revision = binding['contentRevision']
            require(binding['cacheFile'] == cache_name(binding['pageIdentity'], revision, digest),
                    'original cache page/revision binding mismatch')
            require((key, revision) not in bindings, 'duplicate original cache binding')
            bindings[key, revision] = (digest, dimensions)
    observed = {}
    for placement in placements:
        key = identity(placement['pageIdentity'])
        # This report covers one document. Other episodes need their own original document report.
        if key[:3] != tuple(plan['episodeIdentity'][k] for k in ('sourceId', 'seriesKey', 'episodeKey')):
            continue
        require(key in expected, 'captured page is absent from complete original document')
        require(placement['contentRevision'] == plan['contentRevision'], 'captured page uses another document revision')
        binding = bindings.get((key, plan['contentRevision']))
        require(binding is not None, 'captured page has no exact original cache binding')
        digest, dimensions = binding
        require(placement['sourceSha256'] == digest and
                (placement['sourceWidth'], placement['sourceHeight']) == dimensions,
                'captured source digest/dimensions disagree with original')
        observed[key] = {'pageIdentity': placement['pageIdentity'], 'sourceSha256': digest,
                         'sourceHeight': dimensions[1], 'sourceWidth': dimensions[0]}
    require(pixel_report.get('independentCapturedPixelsVerified') is True, 'independent pixel verification failed')
    relevant_frames = []
    for frame in pixel_report['frames']:
        require(frame.get('capturedPixelsMatch') is True, 'frame pixels do not match')
        bands = [band for band in frame['sourceBands'] if identity(band['pageIdentity']) in expected]
        require(all(identity(b['pageIdentity']) in observed for b in bands), 'pixel band has no captured source inventory')
        relevant_frames.append({**frame, 'sourceBands': bands})
    known = coverage(list(observed.values()), [{**pixel_report, 'frames': relevant_frames}]) if observed else None
    missing = [page['pageIdentity'] for key, page in expected.items() if key not in observed]
    return {'independentDocumentPageOrderVerified': True, 'documentSha256': document['documentSha256'],
            'contentRevision': plan['contentRevision'], 'episodeIdentity': plan['episodeIdentity'],
            'expectedPages': len(expected), 'availableOriginalPages': len(observed), 'missingOriginalPages': missing,
            'knownPageRowCoverage': known,
            'allEpisodeSourceRowsObserved': not missing and known is not None and known['allDeclaredSourceRowsObserved'],
            'sourceResponseBytesBindingVerified': False, 'independentEpisodeCatalogOrderVerified': False,
            'finalStopVerified': False, 'wholeEpisodeVerified': False, 'corpusCredit': 0}


def verify(root, plan_path):
    root, plan_path = Path(root).resolve(), Path(plan_path).resolve()
    raw = plan_path.read_bytes()
    plan = json.loads(raw)
    require(re.fullmatch(r'document-[0-9]+\.html', plan['documentFile']) is not None, 'invalid document filename')
    pixels = verify_pixels(root)  # Revalidate SF/native hashes and actual original pixels, never trust a saved verdict.
    collection = json.loads((root / 'collection.json').read_bytes())
    require(len(collection['captureDirectories']) == 1, 'capture directory is ambiguous')
    name = collection['captureDirectories'][0]
    require(re.fullmatch(r'engine-capture-[0-9]+', name) is not None, 'invalid capture directory')
    placements = [p for file in (root / name).glob('frame-*.json')
                  for p in json.loads(file.read_bytes())['placements']]
    source_path = root / 'original-sources' / 'manifest.json'
    report = inventory(plan, plan_path.with_name(plan['documentFile']).read_bytes(), placements,
                       json.loads(source_path.read_bytes()), source_path.parent, pixels)
    report['planSha256'] = hashlib.sha256(raw).hexdigest()
    report['sourceManifestSha256'] = hashlib.sha256(source_path.read_bytes()).hexdigest()
    report['surfaceReportSha256'] = pixels['surfaceReportSha256']
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        report = verify(args.directory, args.plan)
    except (OSError, ValueError, KeyError, TypeError) as failure:
        report = {'independentDocumentPageOrderVerified': False, 'error': str(failure), 'corpusCredit': 0}
    args.output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ('knownPageRowCoverage', 'missingOriginalPages')}))
    raise SystemExit(0 if report.get('allEpisodeSourceRowsObserved') else 1)
