import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from engine_cache_identity import cache_name
from export_engine_capture_sources import export


class ExportTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        capture = self.root / 'engine-capture-1'
        capture.mkdir()
        (self.root / 'collection.json').write_text(json.dumps(dict(success=True, traceStopped=True,
            captureDirectories=[capture.name])))
        (capture / 'ownership.json').write_text(json.dumps(dict.fromkeys(('queued', 'active', 'retiring',
            'subscribers', 'retainedResults', 'fileLeases', 'preparedPages', 'pendingPublications'), 0)))
        self.body = b'one shared original body'
        self.digest = hashlib.sha256(self.body).hexdigest()
        self.pages = [dict(sourceId='wfwf', seriesKey='comic:1', episodeKey='1', pageKey=f'p{i:04d}') for i in range(2)]
        self.names = [cache_name(p, 'revision', self.digest) for p in self.pages]
        (capture / 'frame-0.json').write_text(json.dumps(dict(placements=[dict(pageIdentity=p,
            contentRevision='revision', sourceSha256=self.digest) for p in self.pages])))

    def run_export(self, names, bodies=None):
        calls = []
        def read(command, stdout, **kwargs):
            name = command[-1].rsplit('/', 1)[-1]
            calls.append(name)
            stdout.write((bodies or {}).get(name, self.body))
        with patch('export_engine_capture_sources._validate_device'), \
             patch('export_engine_capture_sources._adb_checked', return_value='\n'.join(names)), \
             patch('export_engine_capture_sources._adb_command', side_effect=lambda adb, *args: list(args)), \
             patch('export_engine_capture_sources.subprocess.run', side_effect=read):
            result = export('adb', self.root)
        return result, calls

    def test_duplicate_original_reads_every_exact_cache_object(self):
        result, calls = self.run_export(self.names)
        self.assertEqual(sorted(calls), sorted(self.names))
        self.assertEqual(len(result['sources']), 1)
        self.assertEqual(len(result['sources'][0]['cacheBindings']), 2)
        self.assertEqual((self.root / 'original-sources' / (self.digest + '.page')).read_bytes(), self.body)

    def test_same_digest_from_other_page_is_insufficient(self):
        with self.assertRaisesRegex(ValueError, 'exact page/revision'):
            self.run_export(self.names[:1])
        self.assertFalse(json.loads((self.root / 'original-sources' / 'manifest.json').read_text())['success'])

    def test_corrupt_second_shared_object_is_detected(self):
        with self.assertRaisesRegex(ValueError, 'digest mismatch'):
            self.run_export(self.names, {sorted(self.names)[1]: b'corrupt'})


if __name__ == '__main__':
    unittest.main()
