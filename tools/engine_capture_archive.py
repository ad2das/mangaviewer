"""Remove only a fully hash-matched device diagnostic copy after archiving it on the host."""
import hashlib
import json
from pathlib import Path
import re

from collect_engine_readback_fixture import PACKAGE, _adb_checked


def archive_device_trace(adb, root, remote, *, stopped):
    """Retire this collector's stopped trace only after verifying its original host bytes."""
    if stopped is not True or not re.fullmatch(
            r'/data/misc/perfetto-traces/engine-live-[0-9]+\.perfetto-trace', remote):
        raise ValueError('trace must be a stopped live-collector diagnostic')
    root = Path(root).resolve()
    local = root / 'display.perfetto-trace'
    if local.is_symlink() or not local.is_file():
        raise ValueError('original trace archive is missing or indirect')
    with local.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    def matched():
        fields = _adb_checked(adb, 'shell', 'sha256sum', remote).strip().split()
        if fields != [digest, remote]:
            raise ValueError('device trace differs from original host archive')
    matched()
    report = {'remoteFile': remote, 'originalHostFile': local.name, 'sha256': digest,
              'bytes': local.stat().st_size, 'traceStopped': True, 'deviceCopyRemoved': False}
    receipt = root / 'device-trace-archive.json'
    receipt.write_text(json.dumps(report, indent=2), encoding='utf-8')
    matched()
    _adb_checked(adb, 'shell', 'rm', remote)
    report['deviceCopyRemoved'] = True
    receipt.write_text(json.dumps(report, indent=2), encoding='utf-8')
    return report


def archive_device_capture(adb, root, name):
    root = Path(root).resolve()
    if not re.fullmatch(r'engine-capture-[0-9]+', name):
        raise ValueError('invalid diagnostic capture directory')
    remote = f'/sdcard/Android/data/{PACKAGE}/files/{name}'
    local = root / name
    hashes = {}
    lines = _adb_checked(adb, 'shell', 'find', remote, '-type', 'f', '-exec', 'sha256sum', '{}', '+').splitlines()
    for line in lines:
        digest, path = line.split(None, 1)
        if not re.fullmatch(r'[0-9a-f]{64}', digest) or not path.startswith(remote + '/'):
            raise ValueError('unexpected remote diagnostic hash/path')
        relative = path[len(remote) + 1:]
        target = (local / relative).resolve()
        if not target.is_relative_to(local) or target.is_symlink() or not target.is_file() or relative in hashes:
            raise ValueError('invalid or duplicated archived diagnostic path')
        with target.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != digest:
                raise ValueError('device diagnostic differs from its host archive')
        hashes[relative] = digest
    if not hashes:
        raise ValueError('device diagnostic directory is empty')
    report = {'remoteDirectory': remote, 'allRemoteFilesMatched': True, 'files': hashes, 'deviceCopyRemoved': False}
    receipt = root / 'device-capture-archive.json'
    receipt.write_text(json.dumps(report, indent=2), encoding='utf-8')
    # Exact validated diagnostic directory; application originals, database and cache are separate paths.
    _adb_checked(adb, 'shell', 'rm', '-r', remote)
    report['deviceCopyRemoved'] = True
    receipt.write_text(json.dumps(report, indent=2), encoding='utf-8')
    return {'deviceCopyRemoved': True, 'matchedFiles': len(hashes), 'receipt': receipt.name}
