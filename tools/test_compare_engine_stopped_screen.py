import copy
import hashlib
from pathlib import Path
import tempfile
import unittest

from PIL import Image

from compare_engine_stopped_screen import compare_screen


class StoppedScreenTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        original = self.root / 'original.png'
        Image.new('RGB', (2, 3), 'green').save(original)
        digest = hashlib.sha256(original.read_bytes()).hexdigest()
        original.rename(self.root / (digest + '.page'))
        self.png = self.root / 'screenshot.png'
        screen = Image.new('RGB', (4, 7), 'blue')
        screen.paste(Image.new('RGB', (2, 3), 'green'), (1, 2))
        screen.save(self.png)
        page = dict(sourceId='wfwf', seriesKey='comic:1', episodeKey='1', pageKey='p0000')
        scene = dict(token=10, swapSucceeded=True, surfaceLeft=1, surfaceTop=2, surfaceWidth=2, surfaceHeight=3,
            width=2, viewportHeight=3, coordinateUnitsPerPixel=1, placements=[dict(pageIdentity=page,
                sourceSha256=digest, sourceWidth=2, sourceHeight=3, sourceTop=0, sourceBottom=3,
                displayWidth=2, rasterHeight=3, rasterTop=0, rasterBottom=3, screenTopUnits=0, screenBottomUnits=3)])
        self.record = dict(kind='UI_AUTOMATION_COMPOSITED_SCREENSHOT', forcedScene=False, nativeReadback=False,
            before=dict(scene, observedMonotonicNs=1), after=dict(scene, observedMonotonicNs=4),
            captureStartedMonotonicNs=2, captureCompletedMonotonicNs=3, screenWidth=4, screenHeight=7)

    def verify(self):
        return compare_screen(self.record, self.png, self.root)

    def test_observed_surface_rectangle_selects_correct_original_pixels(self):
        result = self.verify()
        self.assertTrue(result['compositedScreenshotPixelsVerified'])
        self.assertEqual(result['maxRowRgbMeanAbsoluteError'], 0)
        self.assertFalse(result['finalStopVerified'])
        self.assertFalse(result['producerLayerBindingVerified'])

    def test_changed_scene_during_acquisition_is_rejected(self):
        self.record['after'] = copy.deepcopy(self.record['after'])
        self.record['after']['token'] += 1
        with self.assertRaisesRegex(ValueError, 'scene changed'):
            self.verify()

    def test_wrong_rectangle_cannot_pass_pixel_comparison(self):
        self.record['before']['surfaceLeft'] = 0
        self.record['after']['surfaceLeft'] = 0
        self.assertFalse(self.verify()['compositedScreenshotPixelsVerified'])

    def test_practical_mode_accepts_identical_content_resubmission_only(self):
        self.record['after'] = copy.deepcopy(self.record['after'])
        for key in ('token', 'eglFrameId', 'timestampNanos', 'submittedAtNanos', 'geometryRevision'):
            self.record['after'][key] = self.record['after'].get(key, 0) + 1
        with self.assertRaisesRegex(ValueError, 'scene changed'):
            self.verify()
        result = compare_screen(self.record, self.png, self.root, require_same_submission=False)
        self.assertTrue(result['compositedScreenshotPixelsVerified'])
        self.assertFalse(result['finalStopVerified'])
        self.assertEqual(len(result['viewportPixelsSha256']), 64)
        Image.new('RGB', (4, 7), 'red').save(self.png)
        self.assertFalse(compare_screen(self.record, self.png, self.root, False)['compositedScreenshotPixelsVerified'])

    def test_practical_mode_rejects_geometry_content_and_input_changes(self):
        original = copy.deepcopy(self.record)
        for key, value in (('surfaceLeft', 0), ('anchor', {'offset': 1}), ('epoch', 2),
                           ('inputRevision', 3), ('placements', [])):
            with self.subTest(key=key):
                self.record = copy.deepcopy(original)
                self.record['after'][key] = value
                with self.assertRaisesRegex(ValueError, 'scene changed'):
                    compare_screen(self.record, self.png, self.root, False)

    def test_outside_rectangle_and_inverted_clock_are_rejected(self):
        self.record['captureCompletedMonotonicNs'] = 1
        with self.assertRaisesRegex(ValueError, 'clock interval'):
            self.verify()
        self.record['captureCompletedMonotonicNs'] = 3
        self.record['before']['surfaceTop'] = 6
        self.record['after']['surfaceTop'] = 6
        with self.assertRaisesRegex(ValueError, 'outside screenshot'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
