import copy
import unittest
from verify_engine_input_observations import verify_rows


class InputObservationTest(unittest.TestCase):
    def setUp(self):
        self.row = dict(ordinal=1, sessionId=1, generation=1, inputRevision=1, geometryRevision=1,
            pendingInputCount=1, sequence=1, gestureId=1, eventTimeNanos=10, deltaScreenUnits=100,
            acceptedAtNanos=11, resolvedAtNanos=None, appliedScreenUnits=30, receiptGeometryRevision=1,
            outcome='DEFERRED', boundary=None)
        self.resolved = dict(self.row, ordinal=2, pendingInputCount=0, resolvedAtNanos=30,
            appliedScreenUnits=100, outcome='APPLIED')

    def test_deferred_and_terminal_history(self):
        report = verify_rows([self.row, self.resolved])
        self.assertEqual(report['acceptedInputCount'], 1)
        self.assertEqual(report['deferredObservationCount'], 1)

    def test_missing_terminal_and_duplicate_terminal_rejected(self):
        for rows in ([self.row], [self.row, self.resolved, dict(self.resolved, ordinal=3)]):
            with self.assertRaises(ValueError):
                verify_rows(rows)

    def test_identity_distance_order_and_clock_mutations_rejected(self):
        for field, value in [('acceptedAtNanos', 12), ('ordinal', 3), ('appliedScreenUnits', 99),
                             ('resolvedAtNanos', 9), ('sequence', 2), ('inputRevision', 2)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_rows([self.row, dict(self.resolved, **{field: value})])

    def test_partial_deferred_progress_cannot_reverse(self):
        with self.assertRaisesRegex(ValueError, 'already applied'):
            verify_rows([self.row, dict(self.row, ordinal=2, appliedScreenUnits=29), dict(self.resolved, ordinal=3)])

    def test_independent_close_counts_reject_a_valid_but_truncated_prefix(self):
        rows = [dict(self.resolved, ordinal=1), dict(self.resolved, ordinal=2, sequence=2, inputRevision=2)]
        proof = dict(sessionId=1, generation=1, inputRevision=2, receivedInputCount=2, observationCount=2, closedAtNanos=40)
        self.assertTrue(verify_rows(rows, proof)['completeSessionInputHistoryVerified'])
        self.assertTrue(verify_rows(rows[:1])['inputHistoryVerified'])
        with self.assertRaisesRegex(ValueError, 'suffix'):
            verify_rows(rows[:1], proof)
        with self.assertRaisesRegex(ValueError, 'count disagrees'):
            verify_rows(rows, dict(proof, receivedInputCount=3))
        with self.assertRaisesRegex(ValueError, 'outside'):
            verify_rows(rows, dict(proof, closedAtNanos=29))

    def test_zero_input_closed_session(self):
        proof = dict(sessionId=1, generation=1, inputRevision=0, receivedInputCount=0, observationCount=0, closedAtNanos=40)
        self.assertTrue(verify_rows([], proof)['completeSessionInputHistoryVerified'])


if __name__ == '__main__':
    unittest.main()
