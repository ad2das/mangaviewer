import unittest
from verify_engine_raw_clock import verify_brackets, raw_prints
from test_audit_engine_foreign_timeline import scalar, message


class RawClockTest(unittest.TestCase):
    def rows(self):
        return [{'ts': 100, 'tid': 9, 'text': 'B|7|viewer_clock'},
                {'ts': 130, 'tid': 9, 'text': 'B|7|viewer_swap:1:4:115'}]

    def test_bracket_retains_original_values(self):
        self.assertEqual(verify_brackets(self.rows(), [1], 7)[0]['nativeMonotonicNs'], 115)

    def test_missing_suffix_duplicate_and_foreign_owner_fail(self):
        for rows, tokens, owner in [(self.rows()[:1], [1], 7), (self.rows(), [1, 2], 7),
                                    (self.rows() * 2, [1], 7), (self.rows(), [1], 8)]:
            with self.subTest(rows=rows, tokens=tokens, owner=owner), self.assertRaises(ValueError):
                verify_brackets(rows, tokens, owner)

    def test_outside_bracket_never_gets_a_tolerance(self):
        for native in (99, 131):
            rows = self.rows()
            rows[1]['text'] = f'B|7|viewer_swap:1:4:{native}'
            with self.subTest(native=native), self.assertRaisesRegex(ValueError, 'outside'):
                verify_brackets(rows, [1], 7)

    def test_expected_token_set_must_itself_be_unique_and_typed(self):
        for tokens in ([1, 1], [True], [], [0]):
            with self.subTest(tokens=tokens), self.assertRaises(ValueError):
                verify_brackets(self.rows(), tokens, 7)

    def test_raw_parser_retains_newline_marker_and_rejects_loss(self):
        event = scalar(1, 100) + scalar(2, 9) + message(3, message(2, b'B|7|viewer_clock\n'))
        data = message(1, message(1, message(2, event)))
        self.assertEqual(raw_prints(data)[0], self.rows()[:1])
        with self.assertRaisesRegex(ValueError, 'loss'):
            raw_prints(message(1, message(1, scalar(3, 1) + message(2, event))))


if __name__ == '__main__':
    unittest.main()
