import unittest

import numpy as np
import PIL
from PIL import Image

from engine_source_sampling import COEFFICIENT_SCALE, reference_identity, resize_weights, sampled_rows, row_ranges
from engine_source_row_coverage import sampling_coverage


class SourceSamplingTest(unittest.TestCase):
    def test_pinned_filter_matches_actual_pillow_impulse_images(self):
        self.assertEqual(PIL.__version__, '12.3.0')
        for source, raster in ((3, 5), (9, 4), (7, 7), (4, 17), (1098, 1647)):
            with self.subTest(source=source, raster=raster):
                # Each column independently excites one original row. This tests
                # the actual library against the analytic filter, including zeros.
                basis = np.eye(source, dtype=np.uint8) * 255
                actual = np.asarray(Image.fromarray(basis).resize((source, raster), Image.Resampling.BILINEAR))
                predicted = np.zeros((raster, source), dtype=np.uint8)
                for output in range(raster):
                    for row, weight in resize_weights(source, raster, output):
                        predicted[output, row] = min(255, (255 * weight + COEFFICIENT_SCALE // 2) // COEFFICIENT_SCALE)
                np.testing.assert_array_equal(actual, predicted)

    def placement(self):
        return dict(sourceHeight=6, rasterHeight=6, rasterTop=0, rasterBottom=6,
                    sourceTop=0, sourceBottom=6, screenTopUnits=0, screenBottomUnits=6144)

    def test_zero_weight_neighbor_is_not_counted(self):
        p = self.placement()
        self.assertEqual(sampled_rows(p, 1024, 2, 3), {2})
        self.assertEqual(row_ranges(sampled_rows(p, 1024, 2, 4)), [[2, 4]])

    def test_missing_middle_pixel_does_not_get_filled_by_endpoints(self):
        p = self.placement()
        observed = sampled_rows(p, 1024, 0, 2) | sampled_rows(p, 1024, 3, 6)
        self.assertNotIn(2, observed)
        self.assertEqual(row_ranges(observed), [[0, 2], [3, 6]])

    def test_fractional_edge_has_real_row_support_but_clipped_row_is_missing(self):
        p = self.placement()
        p.update(screenTopUnits=600, screenBottomUnits=6744)
        self.assertIn(0, sampled_rows(p, 1024, 1, 7))
        self.assertNotIn(0, sampled_rows(p, 1024, 2, 7))

    def test_texture_crop_does_not_credit_adjacent_source_rows(self):
        p = self.placement()
        p.update(sourceTop=2, sourceBottom=4, rasterTop=2, rasterBottom=4,
                 screenTopUnits=0, screenBottomUnits=2048)
        self.assertEqual(sampled_rows(p, 1024, 0, 2), {2, 3})

    def test_aggregation_does_not_fill_missing_rows_or_inflate_duplicates(self):
        page = dict(pageIdentity=dict(sourceId='test', seriesKey='s', episodeKey='e', pageKey='p'),
                    sourceSha256='a' * 64, sourceHeight=6)
        band = dict(pageIdentity=page['pageIdentity'], sourceSha256=page['sourceSha256'],
                    sampledSourceRowRanges=[[0, 2], [3, 6]])
        frame = dict(capturedPixelsMatch=True, sourceBands=[band])
        report = dict(independentCapturedPixelsVerified=True, sourceSamplingReference=reference_identity(),
                      frames=[frame, frame])
        result = sampling_coverage([page], [report])
        self.assertFalse(result['allDeclaredSourceRowsSampledInReference'])
        self.assertEqual(result['pages'][0]['referenceSampledSourceRows'], 5)
        self.assertEqual(result['pages'][0]['missingReferenceSourceRowRanges'], [[2, 3]])
        band['sampledSourceRowRanges'].append([2, 3])
        self.assertTrue(sampling_coverage([page], [report])['allDeclaredSourceRowsSampledInReference'])
        band['sourceSha256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'source version'):
            sampling_coverage([page], [report])
        band['sourceSha256'] = page['sourceSha256'] = 'not-a-digest'
        with self.assertRaisesRegex(ValueError, 'digest is invalid'):
            sampling_coverage([page], [report])

    def test_unverified_pixels_cannot_supply_sampling_proof(self):
        page = dict(pageIdentity=dict(sourceId='test', seriesKey='s', episodeKey='e', pageKey='p'),
                    sourceSha256='a' * 64, sourceHeight=6)
        report = dict(independentCapturedPixelsVerified=False, sourceSamplingReference=reference_identity(), frames=[{}])
        with self.assertRaisesRegex(ValueError, 'verified captured pixels'):
            sampling_coverage([page], [report])


if __name__ == '__main__':
    unittest.main()
