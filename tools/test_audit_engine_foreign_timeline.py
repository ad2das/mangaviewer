import copy
import unittest

from google.protobuf.internal.encoder import _VarintBytes

from audit_engine_foreign_timeline import candidates, audit


def scalar(number, value):
    return _VarintBytes(number << 3) + _VarintBytes(value)


def message(number, value):
    return _VarintBytes(number << 3 | 2) + _VarintBytes(len(value)) + value


class ForeignTimelineTest(unittest.TestCase):
    def setUp(self):
        self.processes = [dict(pid=476, uid=1000, name='/system/bin/surfaceflinger'),
                          dict(pid=1068, uid=10180, name='com.google.android.apps.nexuslauncher')]
        self.layer = b'TX - com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity#1'

    def trace(self, pid=1068, layer=None, sender=476):
        def packet(kind, event, timestamp):
            return message(1, scalar(58, 6) + scalar(8, timestamp) + message(76, message(kind, event)) +
                           scalar(3, 1000) + scalar(10, 6) + scalar(79, sender))
        return packet(4, scalar(1, 30049) + scalar(4, pid) + message(5, layer or self.layer), 1000) + \
            packet(5, scalar(1, 30049), (1 << 63) - 5801)

    def test_exact_foreign_launcher_candidate_is_located(self):
        result = candidates(self.trace(), 2000, 10236, self.processes)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]['cookie'], 30049)
        self.assertEqual(result[0]['pid'], 1068)
        self.assertGreater(result[0]['startOffset'], 0)

    def test_viewer_pid_and_viewer_uid_are_not_excluded(self):
        with self.assertRaisesRegex(ValueError, 'belongs to the viewer'):
            candidates(self.trace(), 1068, 10236, self.processes)
        processes = copy.deepcopy(self.processes)
        processes[1]['uid'] = 10236
        with self.assertRaisesRegex(ValueError, 'process/UID'):
            candidates(self.trace(), 2000, 10236, processes)

    def test_other_layers_and_untrusted_senders_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'launcher layer'):
            candidates(self.trace(layer=b'TX - ml.melun.mangaview/ViewerActivity#1'), 2000, 10236, self.processes)
        with self.assertRaisesRegex(ValueError, 'trusted SurfaceFlinger'):
            candidates(self.trace(sender=5), 2000, 10236, self.processes)

    def test_other_trace_errors_cannot_enter_the_exception(self):
        for stats in ([dict(name='ftrace_lost_events', value=1)],
                      [dict(name='trace_sorter_negative_timestamp_dropped', value=1), dict(name='other', value=1)]):
            with self.subTest(stats=stats), self.assertRaisesRegex(ValueError, 'unaudited'):
                audit('unused', stats, 2000, 10236)

    def test_ambiguous_cookie_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'ambiguous'):
            candidates(self.trace() + self.trace(), 2000, 10236, self.processes)

    def test_only_exact_main_activity_before_tap_can_be_scoped_out(self):
        processes = self.processes + [dict(pid=2000, uid=10236, name='ml.melun.mangaview')]
        layer = b'TX - ml.melun.mangaview/ml.melun.mangaview.activity.MainActivity#12'
        raw = self.trace(pid=2000, layer=layer)
        result = candidates(raw, 2000, 10236, processes, before_boot_ns=2000)
        self.assertEqual(result[0]['scope'], 'MAIN_ACTIVITY_BEFORE_EPISODE_TAP')
        processes[-1]['name'] = 'melun.mangaview'
        self.assertEqual(candidates(raw, 2000, 10236, processes, before_boot_ns=2000)[0]['processName'],
                         'melun.mangaview')
        processes[-1]['name'] = 'ml.melun.mangaview'
        for cutoff in (999, 1000):
            with self.subTest(cutoff=cutoff), self.assertRaisesRegex(ValueError, 'overlaps'):
                candidates(raw, 2000, 10236, processes, before_boot_ns=cutoff)
        with self.assertRaisesRegex(ValueError, 'belongs to the viewer'):
            candidates(self.trace(pid=2000, layer=layer.replace(b'MainActivity', b'ViewerActivity')),
                       2000, 10236, processes, before_boot_ns=2000)
        with self.assertRaisesRegex(ValueError, 'process/UID'):
            candidates(raw, 2000, 555, processes, before_boot_ns=2000)

    def test_status_bar_requires_foreign_systemui_identity_and_prelaunch_start(self):
        processes = self.processes + [dict(pid=853, uid=10181, name='com.android.systemui')]
        raw = self.trace(pid=853, layer=b'TX - StatusBar#79')
        result = candidates(raw, 2000, 10236, processes, before_boot_ns=2000)
        self.assertEqual(result[0]['scope'], 'STATUS_BAR_BEFORE_EPISODE_TAP')
        for cutoff in (999, 1000):
            with self.subTest(cutoff=cutoff), self.assertRaisesRegex(ValueError, 'overlaps'):
                candidates(raw, 2000, 10236, processes, before_boot_ns=cutoff)
        for changes in ({'uid': 10236}, {'name': 'other'}, {'uid': None}):
            changed = copy.deepcopy(processes)
            changed[-1].update(changes)
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, 'process/UID'):
                candidates(raw, 2000, 10236, changed, before_boot_ns=2000)
        with self.assertRaisesRegex(ValueError, 'launcher layer'):
            candidates(raw, 2000, 10236, processes)
        with self.assertRaisesRegex(ValueError, 'belongs to the viewer'):
            candidates(raw, 853, 10236, processes, before_boot_ns=2000)


if __name__ == '__main__':
    unittest.main()
