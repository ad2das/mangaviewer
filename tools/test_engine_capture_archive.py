import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from engine_capture_archive import archive_device_capture, archive_device_trace


class ArchiveTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'engine-capture-1').mkdir()
        (self.root / 'engine-capture-1' / 'native-0.packet.gz').write_bytes(b'archived')
        self.remote = '/sdcard/Android/data/ml.melun.mangaview/files/engine-capture-1'

    def test_removes_only_the_exact_fully_matched_diagnostic(self):
        digest = hashlib.sha256(b'archived').hexdigest()
        with patch('engine_capture_archive._adb_checked', side_effect=[
                f'{digest}  {self.remote}/native-0.packet.gz\n', '']) as adb:
            result = archive_device_capture('adb', self.root, 'engine-capture-1')
        self.assertTrue(result['deviceCopyRemoved'])
        self.assertEqual(adb.call_args.args, ('adb', 'shell', 'rm', '-r', self.remote))
        self.assertTrue((self.root / 'engine-capture-1' / 'native-0.packet.gz').exists())

    def test_hash_mismatch_never_deletes_device_copy(self):
        with patch('engine_capture_archive._adb_checked', return_value=f'{"a" * 64}  {self.remote}/native-0.packet.gz') as adb:
            with self.assertRaisesRegex(ValueError, 'differs'):
                archive_device_capture('adb', self.root, 'engine-capture-1')
        self.assertEqual(adb.call_count, 1)

    def test_non_diagnostic_path_never_calls_adb(self):
        with patch('engine_capture_archive._adb_checked') as adb:
            for name in ('../app_engine_pages_v1', 'engine-capture-1/..', 'files', ''):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    archive_device_capture('adb', self.root, name)
        adb.assert_not_called()

    def test_stopped_trace_requires_two_matches_and_preserves_original(self):
        local = self.root / 'display.perfetto-trace'
        local.write_bytes(b'original trace')
        remote = '/data/misc/perfetto-traces/engine-live-12.perfetto-trace'
        line = f'{hashlib.sha256(local.read_bytes()).hexdigest()}  {remote}'
        with patch('engine_capture_archive._adb_checked', side_effect=[line, line, '']) as adb:
            result = archive_device_trace('adb', self.root, remote, stopped=True)
        self.assertTrue(result['deviceCopyRemoved'])
        self.assertEqual(adb.call_args.args, ('adb', 'shell', 'rm', remote))
        self.assertEqual(local.read_bytes(), b'original trace')

    def test_changed_trace_is_never_deleted(self):
        local = self.root / 'display.perfetto-trace'
        local.write_bytes(b'original trace')
        remote = '/data/misc/perfetto-traces/engine-live-12.perfetto-trace'
        line = f'{hashlib.sha256(local.read_bytes()).hexdigest()}  {remote}'
        with patch('engine_capture_archive._adb_checked', side_effect=[line, 'changed']) as adb:
            with self.assertRaisesRegex(ValueError, 'differs'):
                archive_device_trace('adb', self.root, remote, stopped=True)
        self.assertEqual(adb.call_count, 2)

    def test_live_or_unowned_trace_never_calls_adb(self):
        with patch('engine_capture_archive._adb_checked') as adb:
            for remote, stopped in [('/data/misc/perfetto-traces/engine-live-1.perfetto-trace', False),
                                    ('/data/misc/perfetto-traces/user.perfetto-trace', True)]:
                with self.subTest(remote=remote, stopped=stopped), self.assertRaises(ValueError):
                    archive_device_trace('adb', self.root, remote, stopped=stopped)
        adb.assert_not_called()


if __name__ == '__main__':
    unittest.main()
