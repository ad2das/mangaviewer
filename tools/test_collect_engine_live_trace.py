import unittest

from collect_engine_live_trace import _engine_diagnostics_args


class CollectEngineLiveTraceTest(unittest.TestCase):
    def test_engine_diagnostics_forwarding_is_explicit_and_default_off(self):
        self.assertEqual([], _engine_diagnostics_args(False))
        self.assertEqual(
            ['-e', 'captureEngineDiagnostics', 'true'],
            _engine_diagnostics_args(True),
        )


if __name__ == '__main__':
    unittest.main()
