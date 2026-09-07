import copy
import unittest
import hashlib
import tempfile
from pathlib import Path
import numpy as np
from PIL import Image
from compare_engine_capture_pixels import compare
from engine_raster_profile import MODEL, validate_cases, validate_frame
from engine_raster_edge_model import predicted_upper_row


class RasterProfileTest(unittest.TestCase):
    def cases(self):
        cases = []
        for row in (2, 65, 155, 1069, 1712, 2135):
            for offset in (508, 509, 510, 511, 512, 513, 514, 515, 516):
                edge = row * 1024 + offset
                rows = []
                for y in (row - 1, row, row + 1):
                    upper = predicted_upper_row(y, edge, 2138)
                    rows.append(dict(row=y, upperPixels=1080 if upper else 0, lowerPixels=0 if upper else 1080, otherPixels=0))
                cases.append(dict(edgeUnits=edge, subpixelBits=8, sampleBuffers=0, samples=0, rows=rows))
        return cases

    def test_complete_grid_required_and_one_changed_pixel_fails(self):
        cases = self.cases()
        validate_cases(cases)
        with self.assertRaises(ValueError):
            validate_cases(cases[:-1])
        cases[0]['rows'][1]['otherPixels'] = 1
        with self.assertRaises(ValueError):
            validate_cases(cases)

    def test_missing_or_different_capture_metadata_cannot_use_profile(self):
        frame = dict(width=1080, viewportHeight=2138, coordinateUnitsPerPixel=1024,
                     rasterizationInfo=dict(subpixelBits=8, sampleBuffers=0, samples=0))
        validate_frame({'model': MODEL}, frame)
        for changes in ({'width': 720}, {'viewportHeight': 100}, {'rasterizationInfo': None},
                        {'rasterizationInfo': dict(subpixelBits=8, sampleBuffers=False, samples=0)}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_frame({'model': MODEL}, {**frame, **changes})

    def test_boundary_uses_prediction_not_whichever_page_matches_pixels(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            placements = []
            colors = [(224, 32, 32, 255), (32, 64, 224, 255)]
            for index, color in enumerate(colors):
                path = root / 'source.png'
                Image.new('RGBA', (4, 4), color).save(path)
                digest = hashlib.sha256(path.read_bytes()).hexdigest()
                path.rename(root / (digest + '.page'))
                placements.append(dict(sourceSha256=digest, sourceWidth=4, sourceHeight=4,
                    sourceTop=0, sourceBottom=4, displayWidth=1080, rasterHeight=1080,
                    rasterTop=0, rasterBottom=1080, screenTopUnits=67072 + (index - 1) * 1080 * 1024,
                    screenBottomUnits=67072 + index * 1080 * 1024, pageIdentity={'pageKey': str(index)}))
            frame = dict(token=1, width=1080, viewportHeight=2138, coordinateUnitsPerPixel=1024,
                         top=65, bottom=66, placements=placements,
                         rasterizationInfo=dict(subpixelBits=8, sampleBuffers=0, samples=0))
            upper = np.tile(np.array(colors[0], dtype=np.uint8), (1,1080,1)).tobytes()
            lower = np.tile(np.array(colors[1], dtype=np.uint8), (1,1080,1)).tobytes()
            self.assertFalse(compare(frame, upper, root)['capturedPixelsMatch'])
            result = compare(frame, upper, root, {'model': MODEL})
            self.assertTrue(result['capturedPixelsMatch'])
            self.assertEqual(result['sourceBands'][0]['sourceBottomFraction'], [4, 1])
            self.assertFalse(compare(frame, lower, root, {'model': MODEL})['capturedPixelsMatch'])


if __name__ == '__main__':
    unittest.main()
