import unittest
from verify_engine_live_surface import HEADER, MAGIC, verify_native_packet


class NativeCaptureTest(unittest.TestCase):
    def setUp(self):
        self.values = [MAGIC, 1, 1, 7, 1, 1, 4, 4, 1, 0, 1, 10, 20, 15, 4, 0]
        keys = ('sessionId', 'rendererEpoch', 'surfaceEpoch', 'token', 'eglFrameId', 'width', 'top', 'bottom',
                'issuedMonotonicNs', 'readyMonotonicNs', 'swapCompletedMonotonicNs', 'rgbaBytes')
        self.frame = dict(zip(keys, self.values[3:15]))
        self.strip = bytes([1, 2, 3, 255])

    def verify(self):
        verify_native_packet(self.frame, HEADER.pack(*self.values) + self.strip, self.strip)

    def test_exact_native_packet(self):
        self.verify()

    def test_each_header_field_must_match_record(self):
        for key in self.frame:
            original = self.frame[key]
            with self.subTest(key=key):
                self.frame[key] += 1
                with self.assertRaisesRegex(ValueError, 'disagrees'):
                    self.verify()
                self.frame[key] = original

    def test_pixel_mutation_and_truncation_rejected(self):
        raw = HEADER.pack(*self.values) + self.strip
        for packet, strip in ((raw[:-1], self.strip), (raw, b'wrong'), (raw[:10], self.strip)):
            with self.assertRaises(ValueError):
                verify_native_packet(self.frame, packet, strip)

    def test_physical_flag_and_inverted_clock_rejected(self):
        self.values[15] = 1
        with self.assertRaises(ValueError):
            self.verify()
        self.values[15] = 0
        self.values[12] = self.frame['readyMonotonicNs'] = 14
        with self.assertRaisesRegex(ValueError, 'timestamps'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
