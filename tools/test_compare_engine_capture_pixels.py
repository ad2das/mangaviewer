import hashlib
from pathlib import Path
import tempfile
import unittest

import numpy as np
from PIL import Image
from compare_engine_capture_pixels import compare


class CapturePixelTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pixels = np.zeros((6, 4, 4), dtype=np.uint8)
        self.pixels[:, :, 3] = 255
        self.pixels[2, :, 0] = 255
        self.pixels[3, :, 2] = 255
        path = self.root / 'source.png'
        Image.fromarray(self.pixels).save(path)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        path.rename(self.root / (digest + '.page'))
        self.frame = dict(token=7, width=4, top=0, bottom=2, viewportHeight=2, coordinateUnitsPerPixel=1024,
            placements=[dict(sourceSha256=digest, sourceWidth=4, sourceHeight=6, sourceTop=2, sourceBottom=4,
                displayWidth=4, rasterHeight=6, rasterTop=2, rasterBottom=4, screenTopUnits=0, screenBottomUnits=2048,
                pageIdentity=dict(sourceId='test', seriesKey='series', episodeKey='episode', pageKey='page'))])

    def test_crop_orientation_and_source_fraction(self):
        result = compare(self.frame, self.pixels[2:4].tobytes(), self.root)
        self.assertTrue(result['capturedPixelsMatch'])
        self.assertEqual(result['sourceBands'][0]['sourceTopFraction'], [2, 1])
        self.assertEqual(result['sourceBands'][0]['sourceBottomFraction'], [4, 1])

    def test_inverted_or_wrong_pixels_fail(self):
        self.assertFalse(compare(self.frame, self.pixels[2:4][::-1].tobytes(), self.root)['capturedPixelsMatch'])
        self.assertFalse(compare(self.frame, self.pixels[0:2].tobytes(), self.root)['capturedPixelsMatch'])

    def test_geometry_and_content_mismatch_fail(self):
        self.frame['placements'][0]['rasterTop'] = 1
        with self.assertRaisesRegex(ValueError, 'raster crop'):
            compare(self.frame, self.pixels[2:4].tobytes(), self.root)
        self.frame['placements'][0]['rasterTop'] = 2
        next(self.root.glob('*.page')).write_bytes(b'changed source')
        with self.assertRaisesRegex(ValueError, 'digest mismatch'):
            compare(self.frame, self.pixels[2:4].tobytes(), self.root)

    def test_empty_scene_is_reported_as_uncovered_black(self):
        self.frame['placements'] = []
        result = compare(self.frame, self.pixels[0:2].tobytes(), self.root)
        self.assertTrue(result['capturedPixelsMatch'])
        self.assertEqual(result['uncoveredCapturedRows'], 2)
        self.assertEqual(result['sourceBands'], [])
        self.assertFalse(result['wholeEpisodeVerified'])


if __name__ == '__main__':
    unittest.main()
