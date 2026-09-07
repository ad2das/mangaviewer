import unittest
from engine_raster_edge_model import window_edge, predicted_upper_row


class RasterEdgeModelTest(unittest.TestCase):
    def test_recorded_first_control_boundaries(self):
        self.assertFalse(predicted_upper_row(65, 67068, 2138))
        for edge in (67070, 67071, 67072, 67073, 67074, 67076):
            self.assertTrue(predicted_upper_row(65, edge, 2138))
        self.assertTrue(predicted_upper_row(155, 159230, 2138))

    def test_rejects_silent_device_profile_generalization(self):
        for changes in ({'subpixel_bits': 4}, {'units': 1}, {'height': 0}):
            args = dict(edge_units=67072, height=2138)
            args.update(changes)
            with self.assertRaises(ValueError):
                window_edge(**args)


if __name__ == '__main__':
    unittest.main()
