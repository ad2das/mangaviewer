"""Export existing original cache bodies after a closed diagnostic capture, without fetching content."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

from collect_engine_readback_fixture import PACKAGE, _adb_checked, _adb_command, _validate_device
from engine_cache_identity import cache_name


def export(adb, directory):
    root = Path(directory).resolve()
    collection = json.loads((root / 'collection.json').read_text(encoding='utf-8'))
    if collection.get('success') is not True or collection.get('traceStopped') is not True:
        raise ValueError('collection is not complete')
    names = collection['captureDirectories']
    if len(names) != 1 or not re.fullmatch(r'engine-capture-[0-9]+', names[0]):
        raise ValueError('capture directory is ambiguous')
    capture = root / names[0]
    ownership = json.loads((capture / 'ownership.json').read_text(encoding='utf-8'))
    for key in ('queued', 'active', 'retiring', 'subscribers', 'retainedResults',
                'fileLeases', 'preparedPages', 'pendingPublications'):
        if type(ownership.get(key)) is not int or ownership[key] != 0:
            raise ValueError(f'closed ownership is not zero: {key}')
    requested = {}
    placements = []
    for frame in capture.glob('frame-*.json'):
        placements.extend(json.loads(frame.read_text(encoding='utf-8'))['placements'])
    for screen in capture.glob('stopped-screen-*.json'):
        record = json.loads(screen.read_bytes())
        for key in ('before', 'after'):
            placements.extend(record[key]['placements'])
    for placement in placements:
        digest = placement['sourceSha256']
        if not re.fullmatch(r'[0-9a-f]{64}', digest):
            raise ValueError('source digest is invalid')
        page = placement['pageIdentity']
        revision = placement['contentRevision']
        name = cache_name(page, revision, digest)
        requested.setdefault(digest, {})[name] = {'pageIdentity': page, 'contentRevision': revision, 'cacheFile': name}
    _validate_device(adb)
    remote = 'app_engine_pages_v1/pages'
    cache_names = _adb_checked(adb, 'shell', 'run-as', PACKAGE, 'ls', '-1', remote).splitlines()
    output = root / 'original-sources'
    output.mkdir(exist_ok=False)
    manifest = {'readOnlyCacheExport': True, 'networkRequests': 0, 'sources': [], 'success': False}
    try:
        for digest, bindings in requested.items():
            target = output / (digest + '.page')
            for number, name in enumerate(sorted(bindings)):
                if name not in cache_names:
                    raise ValueError(f'exact page/revision body is no longer in cache: {name}')
                # Read and hash each actual cache object, even when several pages share bytes.
                temporary = output / (name + '.partial')
                with temporary.open('xb') as stream:
                    subprocess.run(_adb_command(adb, 'exec-out', 'run-as', PACKAGE, 'cat',
                        remote + '/' + name), stdout=stream, stderr=subprocess.PIPE, check=True)
                with temporary.open('rb') as stream:
                    actual = hashlib.file_digest(stream, 'sha256').hexdigest()
                if actual != digest:
                    raise ValueError(f'exported original digest mismatch: {name}')
                if number == 0:
                    temporary.rename(target)
                else:
                    temporary.unlink()
            manifest['sources'].append({'sha256': digest, 'file': target.name,
                'cacheBindings': list(bindings.values()),
                'observedPageIdentities': [v['pageIdentity'] for v in bindings.values()]})
        manifest['success'] = True
    except Exception as failure:
        manifest['error'] = str(failure)
        raise
    finally:
        (output / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--directory', required=True, type=Path)
    args = parser.parse_args()
    report = export(args.adb, args.directory)
    print(json.dumps({'success': report['success'], 'originals': len(report['sources'])}))
