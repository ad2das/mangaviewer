import unittest
from verify_engine_motion_observations import cadence, verify_history


class MotionHistoryTest(unittest.TestCase):
    def test_omitted_suffix_and_missing_windows_fail(self):
        rows = [dict(ordinal=1, sequence=3, frameTimeNanos=10, appliedAtNanos=11)]
        windows = [dict(ordinal=1, startNanos=1, endNanos=20)]
        proof = dict(observationCount=1, windowCount=1, closedAtNanos=30,
            refreshPeriodNanos=10, historyOverwritten=False, physicalPresentationVerified=False)
        self.assertTrue(verify_history(rows, windows, proof))
        for r, w, p in [(rows, [], proof), ([], windows, proof),
                         (rows, windows, dict(proof, historyOverwritten=True))]:
            with self.assertRaises(ValueError):
                verify_history(r, w, p)

    def test_empty_windows_and_long_edges_preserve_freezes(self):
        windows = [dict(startNanos=1, endNanos=500_000_001)]
        result = cadence([], windows, 16_666_667, 1, 500_000_001)
        self.assertEqual(result['emptyWindowCount'], 1)
        self.assertEqual(result['gapsAtLeast100ms'], 1)
        self.assertEqual(result['missedFrameRatio'], 1)
        result = cadence([200_000_001, 216_666_668], windows, 16_666_667, 1, 500_000_001)
        self.assertEqual(result['gapsAtLeast100ms'], 2)
        self.assertGreater(result['missedFrameRatio'], .9)

    def test_refresh_rounding_and_idle_between_gestures(self):
        windows = [dict(startNanos=1, endNanos=30_000_001),
                   dict(startNanos=1_000_000_001, endNanos=1_030_000_001)]
        result = cadence([1, 10_000_001, 30_000_001, 1_000_000_001, 1_010_000_001, 1_030_000_001],
                         windows, 10_000_000, 1, 1_030_000_001)
        self.assertEqual(result['missedFrameCount'], 2)
        self.assertAlmostEqual(result['missedFrameRatio'], 2/6)
        self.assertEqual(result['gapsAtLeast100ms'], 0)

    def test_vsync_is_assigned_to_the_window_where_its_callback_ran(self):
        result = cadence([10, 20, 30], [dict(startNanos=11, endNanos=39)],
                         10, 11, 39, [12, 22, 32])
        self.assertEqual(result['sampleCount'], 3)
        self.assertEqual(result['cadenceIntervalCount'], 2)
        self.assertEqual(result['missedFrameCount'], 0)
        self.assertEqual(result['maxEdgeMillis'], 7/1e6)


if __name__ == '__main__':
    unittest.main()
