import unittest

from audit_engine_unused_tsc import encode, message, remove_unused_tsc
from audit_engine_foreign_timeline import fields


def scalar(number, value):
    return encode(number << 3) + encode(value)


def snapshot(core, tsc):
    clocks = b''.join(message(1, scalar(1, clock) + scalar(2, stamp))
                      for clock, stamp in ((3, core), (5, core), (6, core), (9, tsc)))
    return message(1, message(6, clocks + scalar(2, 3)))


class UnusedTscTest(unittest.TestCase):
    def setUp(self):
        self.event = message(1, scalar(58, 3) + scalar(8, 101) + message(57, b'original payload'))
        self.trace = snapshot(100, 400) + self.event + snapshot(102, 399)

    def test_only_tsc_snapshot_entries_change(self):
        changed, report = remove_unused_tsc(self.trace)
        self.assertIn(self.event, changed)
        self.assertEqual(len(report['reversedTscSnapshots']), 1)
        clocks = []
        for _, _, packet, _, _ in fields(changed):
            for n, _, value, _, _ in fields(packet):
                if n == 6:
                    clocks.append([dict((k, v) for k, _, v, _, _ in fields(clock))[1]
                                   for k, w, clock, _, _ in fields(value) if k == 1 and w == 2])
        self.assertEqual(clocks, [[3, 5, 6], [3, 5, 6]])

    def test_tsc_timestamped_events_are_rejected(self):
        extra = message(1, scalar(58, 9) + scalar(8, 400) + message(57, b'event'))
        with self.assertRaisesRegex(ValueError, 'timestamp clock'):
            remove_unused_tsc(self.trace + extra)

    def test_unknown_payload_and_defaults_are_rejected(self):
        for number in (11, 50, 59, 107):
            with self.subTest(number=number), self.assertRaisesRegex(ValueError, 'unknown payload'):
                remove_unused_tsc(self.trace + message(1, message(number, b'')))

    def test_used_core_clock_reversal_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'core clock moved backwards'):
            remove_unused_tsc(snapshot(100, 400) + snapshot(99, 399))

    def test_ftrace_unknown_clock_or_data_loss_is_rejected(self):
        for body in (scalar(5, 1), scalar(3, 1), message(8, b'')):
            with self.subTest(body=body), self.assertRaises(ValueError):
                remove_unused_tsc(self.trace + message(1, message(1, body)))

    def test_no_reversal_is_not_evidence_for_this_exception(self):
        with self.assertRaisesRegex(ValueError, 'no reversed'):
            remove_unused_tsc(snapshot(100, 400) + snapshot(102, 401))


if __name__ == '__main__':
    unittest.main()
