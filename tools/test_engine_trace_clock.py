import unittest
from unittest.mock import patch
from engine_trace_clock import RawMonotonicTrace, raw_monotonic_config


class TraceClockTest(unittest.TestCase):
    def setUp(self):
        self.state = {'tracing_on': '0', 'trace_clock': 'mono [boot]', 'buffer_size_kb': '4', 'trace': ''}
        self.clock = RawMonotonicTrace('adb')
        self.clock.read = self.state.__getitem__
        def write(name, value):
            self.state[name] = '[mono] boot' if name == 'trace_clock' and value.strip() == 'mono' else (
                'mono [boot]' if name == 'trace_clock' else value.strip())
        self.clock.write = write

    def test_prepare_observe_and_restore_exact_original_values(self):
        self.clock.prepare()
        self.clock.observe('During')
        self.clock.observe('BeforeStop')
        self.clock.restore(stopped=True)
        self.assertEqual(self.state['buffer_size_kb'], '4')
        self.assertEqual(self.state['trace_clock'], 'mono [boot]')
        self.assertTrue(self.clock.report['restoredOriginalClock'])
        self.assertTrue(self.clock.report['restoredOriginalBuffer'])

    def test_active_trace_prevents_setup_or_restoration(self):
        self.state['tracing_on'] = '1'
        with patch.object(self.clock, 'write') as write, self.assertRaises(ValueError):
            self.clock.prepare()
        write.assert_not_called()
        self.state['tracing_on'] = '0'
        self.clock.prepare()
        with patch.object(self.clock, 'write') as write, self.assertRaises(ValueError):
            self.clock.restore(stopped=False)
        write.assert_not_called()

    def test_partial_setup_failure_still_restores_clock(self):
        original = self.clock.write
        def fail_buffer(name, value):
            if name == 'buffer_size_kb' and value == '4096\n':
                raise OSError('fixture setup failure')
            original(name, value)
        self.clock.write = fail_buffer
        with self.assertRaises(OSError):
            self.clock.prepare()
        self.clock.restore(stopped=True)
        self.assertEqual(self.state['trace_clock'], 'mono [boot]')

    def test_configuration_rejects_conflicting_or_multiple_sources(self):
        base = b'ftrace_config {\n}'
        self.assertIn(b'preserve_ftrace_buffer: true', raw_monotonic_config(base))
        for config in (base + base, base + b'use_monotonic_raw_clock: true', b''):
            with self.assertRaises(ValueError):
                raw_monotonic_config(config)

    def test_kernel_rounded_buffer_is_recorded_and_must_stay_unchanged(self):
        original = self.clock.write
        def rounded(name, value):
            original(name, value)
            if name == 'buffer_size_kb' and value == '4096\n':
                self.state[name] = '4099'
        self.clock.write = rounded
        self.clock.prepare()
        self.clock.observe('During')
        self.assertEqual(self.clock.report['bufferSizeConfiguredKb'], '4099')
        self.state['buffer_size_kb'] = '4103'
        with self.assertRaises(ValueError):
            self.clock.observe('BeforeStop')
        self.clock.restore(stopped=True)


if __name__ == '__main__':
    unittest.main()
