import copy
import unittest

from verify_engine_live_surface import TARGET
from verify_engine_stopped_screen import stable_interval


class StoppedIntervalTest(unittest.TestCase):
    def setUp(self):
        self.snapshot = dict(rendererId=1, sessionId=1, rendererEpoch=1, surfaceEpoch=1, token=10,
                             inputRevision=20, geometryRevision=2, eglFrameId=11)
        self.records = [dict(before=self.snapshot, after=self.snapshot,
            captureStartedMonotonicNs=start, captureCompletedMonotonicNs=start + 10) for start in (2_000_000_000, 3_000_000_100)]
        self.frames = [dict(self.snapshot, submittedAtNanos=1_000_000_000, renderSubmissionDurationNanos=100)]
        self.inputs = [dict(acceptedAtNanos=500_000_000, resolvedAtNanos=600_000_000)]
        self.binding = dict(producerPid=100, producerUid=200, token=10, eglFrameId=11, layerId=30, bufferId=40)
        self.events = [dict(layer_name=TARGET + '#30', name='Latch', ts=1_500_000_000, frame_number=11)]
        self.transactions = []

    def verify(self):
        return stable_interval(self.records, self.frames, self.inputs, self.binding, self.events, self.transactions, 100, 200)

    def test_stable_sealed_buffer_interval(self):
        self.assertEqual(self.verify()['bufferId'], 40)

    def test_newer_submission_or_input_is_not_a_stopped_interval(self):
        self.frames.append(dict(self.frames[0], token=11, submittedAtNanos=2_100_000_000))
        with self.assertRaisesRegex(ValueError, 'newer native'):
            self.verify()
        self.frames.pop()
        self.inputs.append(dict(acceptedAtNanos=2_100_000_000, resolvedAtNanos=None))
        with self.assertRaisesRegex(ValueError, 'input was accepted'):
            self.verify()

    def test_different_compositor_buffer_or_owned_transaction_fails(self):
        self.events.append(dict(self.events[0], ts=2_500_000_000, frame_number=12))
        with self.assertRaisesRegex(ValueError, 'different viewer buffer'):
            self.verify()
        self.events.pop()
        self.transactions.append(dict(uid=200, transactionId=(100 << 32) + 1, postTime=2_500_000_000))
        with self.assertRaisesRegex(ValueError, 'owned buffer transaction'):
            self.verify()

    def test_changed_snapshot_and_short_interval_fail(self):
        self.records[1] = copy.deepcopy(self.records[1])
        self.records[1]['before']['inputRevision'] = 21
        with self.assertRaisesRegex(ValueError, 'scene changed'):
            self.verify()
        self.records[1]['before']['inputRevision'] = 20
        self.records[1]['captureStartedMonotonicNs'] = 2_000_000_001
        with self.assertRaisesRegex(ValueError, 'shorter than one second'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
