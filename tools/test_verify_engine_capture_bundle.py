import unittest
from verify_engine_capture_bundle import stage_verdict


class BundleVerdictTest(unittest.TestCase):
    def test_pixel_false_cannot_pass_even_when_comparison_completed(self):
        result = stage_verdict('pixels-verification', {'independentCapturedPixelsVerified': False, 'frames': [1, 2]})
        self.assertFalse(result['completed'])

    def test_document_http_success_does_not_cover_missing_image_origins(self):
        result = stage_verdict('http-plan-binding-0', {'episodeDocumentHttpBytesVerified': True,
                               'sourceResponseBytesBindingVerified': False})
        self.assertFalse(result['completed'])

    def test_exact_positive_gate_is_required(self):
        for value in ({}, {'finalStopVerified': 1}, {'finalStopVerified': False}):
            self.assertFalse(stage_verdict('stopped-screen-verification', value)['completed'])
        self.assertTrue(stage_verdict('stopped-screen-verification', {'finalStopVerified': True})['completed'])
        with self.assertRaises(ValueError):
            stage_verdict('unknown-stage', {'success': True})


if __name__ == '__main__':
    unittest.main()
