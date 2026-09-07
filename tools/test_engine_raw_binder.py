import copy
import unittest
from engine_raw_binder import normalize_events, events_from_trace, frame_events_from_trace
from verify_engine_surface_fixture import _binder_paths, _binder_indexes
from test_audit_engine_foreign_timeline import scalar, message


class RawBinderTest(unittest.TestCase):
    def setUp(self):
        self.owners = {9: dict(pid=7, uid=10236, name='app'),
                       17: dict(pid=476, uid=1000, name='/system/bin/surfaceflinger')}
        self.events = [dict(kind='begin', ts=10, tid=9, marker_pid=7, name='acquire'),
                       dict(kind='send', ts=12, tid=9, binder_id=5, target_pid=476, flags=1, reply=0),
                       dict(kind='end', ts=15, tid=9, marker_pid=7),
                       dict(kind='receive', ts=20, tid=17, binder_id=5),
                       dict(kind='begin', ts=21, tid=17, marker_pid=476, name='setTransactionState'),
                       dict(kind='end', ts=25, tid=17, marker_pid=476),
                       dict(kind='release', ts=30, tid=17, binder_id=5)]

    def paths(self, events=None):
        rows, flows, releases = normalize_events(events or self.events, self.owners)
        by_id = {row['id']: row for row in rows}
        return _binder_paths(rows[0], by_id, flows, releases, _binder_indexes(by_id, flows, releases))

    def test_exact_raw_message_and_handler_times_are_retained(self):
        path, = self.paths()
        self.assertEqual([row['ts'] for row in path], [12, 20, 30, 21])
        self.assertEqual(path[-1]['dur'], 4)

    def test_wrong_destination_or_message_id_cannot_link(self):
        for index, changes in [(1, {'target_pid': 888}), (3, {'binder_id': 6})]:
            events = copy.deepcopy(self.events)
            events[index].update(changes)
            self.assertEqual(self.paths(events), [])

    def test_duplicate_release_and_out_of_dispatch_handler_fail(self):
        for events in [self.events + [self.events[-1].copy()],
                       [{**e, 'ts': 31} if e['kind'] == 'end' and e['tid'] == 17 else e for e in self.events]]:
            with self.assertRaises(ValueError):
                self.paths(events)

    def test_embedded_pid_cannot_spoof_kernel_thread_owner(self):
        events = copy.deepcopy(self.events)
        events[0]['marker_pid'] = 99
        events[2]['marker_pid'] = 99
        self.assertEqual(self.paths(events), [])

    def test_wire_binder_ids_and_generic_release_are_decoded(self):
        send = scalar(1, 12) + scalar(2, 9) + message(50, scalar(1, 5) + scalar(3, 476) + scalar(7, 1))
        release = scalar(1, 30) + scalar(2, 17) + message(327,
            message(1, b'binder_transaction_buffer_release') + message(2, message(1, b'debug_id') + scalar(4, 5)))
        data = message(1, message(1, message(2, send) + message(2, release)))
        self.assertEqual([e['binder_id'] for e in events_from_trace(data)], [5, 5])
        with self.assertRaisesRegex(ValueError, 'loss'):
            events_from_trace(message(1, message(1, scalar(3, 1))))

    def test_graphics_event_keeps_declared_monotonic_time_and_checks_sender(self):
        def trace(clock=3, uid=1000, pid=476):
            event = scalar(1, 12) + scalar(2, 5) + message(3, b'SurfaceView#10')
            return message(1, scalar(58, clock) + scalar(3, uid) + scalar(79, pid) + scalar(8, 1234) +
                           message(57, message(1, event)))
        row, = frame_events_from_trace(trace(), 476)
        self.assertEqual((row['ts'], row['name'], row['frame_number']), (1234, 'Latch', 12))
        for changes in ({'clock': 6}, {'uid': 7}, {'pid': 8}):
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, 'trusted'):
                frame_events_from_trace(trace(**changes), 476)


if __name__ == '__main__':
    unittest.main()
