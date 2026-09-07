import unittest
from engine_source_row_coverage import coverage


class RowCoverageTest(unittest.TestCase):
    def setUp(self):
        self.page = dict(pageIdentity=dict(sourceId='test', seriesKey='series', episodeKey='ep', pageKey='p'),
                         sourceSha256='a' * 64, sourceHeight=10)

    def report(self, spans):
        return dict(independentCapturedPixelsVerified=True, frames=[dict(capturedPixelsMatch=True,
            sourceBands=[dict(pageIdentity=self.page['pageIdentity'], sourceSha256=self.page['sourceSha256'],
                             sourceTopFraction=a, sourceBottomFraction=b) for a, b in spans])])

    def test_separate_half_rows_combine_without_rounding(self):
        result = coverage([self.page], [self.report([([0, 1], [3, 2]), ([3, 2], [10, 1])])])
        self.assertTrue(result['allDeclaredSourceRowsObserved'])
        self.assertEqual(result['pages'][0]['fullyObservedSourceRows'], 10)
        self.assertFalse(result['wholeEpisodeVerified'])

    def test_tiny_gap_keeps_original_row_incomplete(self):
        result = coverage([self.page], [self.report([([0, 1], [3, 2]), ([1500000001, 1000000000], [10, 1])])])
        self.assertFalse(result['allDeclaredSourceRowsObserved'])
        self.assertEqual(result['pages'][0]['incompleteSourceRowRanges'], [[1, 2]])

    def test_duplicate_and_overlapping_captures_do_not_inflate_coverage(self):
        report = self.report([([2, 1], [5, 1]), ([4, 1], [6, 1])])
        result = coverage([self.page], [report, report])
        self.assertEqual(result['pages'][0]['fullyObservedSourceRows'], 4)
        self.assertEqual(result['pages'][0]['incompleteSourceRowRanges'], [[0, 2], [6, 10]])

    def test_uncaptured_pages_remain_missing(self):
        page2 = dict(self.page, pageIdentity=dict(self.page['pageIdentity'], pageKey='next'))
        result = coverage([self.page, page2], [self.report([([0, 1], [10, 1])])])
        self.assertFalse(result['allDeclaredSourceRowsObserved'])
        self.assertEqual(result['pages'][1]['missingOrPartialSourceRows'], 10)

    def test_wrong_source_or_unverified_pixels_cannot_count(self):
        report = self.report([([0, 1], [10, 1])])
        report['frames'][0]['sourceBands'][0]['sourceSha256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'source version'):
            coverage([self.page], [report])
        with self.assertRaisesRegex(ValueError, 'not been verified'):
            coverage([self.page], [dict(report, independentCapturedPixelsVerified=False)])


if __name__ == '__main__':
    unittest.main()
