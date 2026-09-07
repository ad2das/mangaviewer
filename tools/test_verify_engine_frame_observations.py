import unittest
from verify_engine_frame_observations import verify_rows


class FrameHistoryTest(unittest.TestCase):
    def setUp(self):
        self.rows = [dict(ordinal=i, rendererId=9, sessionId=1, rendererEpoch=1, surfaceEpoch=1, token=i,
            inputRevision=i, geometryRevision=1, submittedAtNanos=i*100_000_000,
            renderSubmissionDurationNanos=cost, timestampKind='SWAP_RETURN', timestampNanos=i*100_000_000+cost,
            swapSucceeded=True, completeViewportCoverage=i==3, visiblePlacementCount=1 if i==3 else 0,
            eglFrameId=i, physicalPresentationVerified=False) for i, cost in enumerate([12_000_000,8_000_000,20_000_000],1)]
        self.proof = dict(rendererId=9, submittedFrameCount=3, deliveredObservationCount=3, closedAtNanos=400_000_000)

    def test_submission_cost_is_not_promoted_to_display_performance(self):
        result = verify_rows(self.rows, self.proof)
        self.assertEqual(result['nativeSubmissionP95Millis'], 20)
        self.assertEqual(result['zeroSourceSceneCount'], 2)
        self.assertIsNone(result['missedDisplayFrameRate'])
        self.assertFalse(result['performanceQualified'])

    def test_suffix_loss_duplicate_token_and_wrong_owner_are_rejected(self):
        for rows in (self.rows[:-1], [self.rows[0], dict(self.rows[1], token=1), self.rows[2]],
                     [dict(self.rows[0], rendererId=8), *self.rows[1:]]):
            with self.assertRaises(ValueError):
                verify_rows(rows, self.proof)

    def test_delivery_order_can_differ_from_submission_order(self):
        rows = [dict(self.rows[i], ordinal=n) for n,i in enumerate([1,0,2],1)]
        self.assertTrue(verify_rows(rows, self.proof)['completeRendererHistoryVerified'])

    def test_unknown_completion_and_failed_swap_are_preserved(self):
        self.rows[2].update(swapSucceeded=False, timestampKind='CANCELLED', timestampNanos=0, eglFrameId=0)
        result = verify_rows(self.rows, self.proof)
        self.assertEqual(result['failedSwapCount'], 1)
        self.assertEqual(result['timestampKinds']['CANCELLED'], 1)


if __name__ == '__main__':
    unittest.main()
