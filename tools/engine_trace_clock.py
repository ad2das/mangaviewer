"""Own and restore the temporary tracefs configuration used for raw MONOTONIC evidence."""
import re
import subprocess

from collect_engine_readback_fixture import _adb_checked, _adb_command


class RawMonotonicTrace:
    def __init__(self, adb):
        self.adb = adb
        self.old_clock = self.old_buffer = None
        self.configured_buffer = None
        self.restored = False
        self.report = {'restoredOriginalClock': False, 'restoredOriginalBuffer': False}

    def read(self, name):
        return _adb_checked(self.adb, 'shell', 'cat', '/sys/kernel/tracing/' + name).strip()

    def write(self, name, value):
        subprocess.run(_adb_command(self.adb, 'shell', 'tee', '/sys/kernel/tracing/' + name),
                       input=value.encode(), capture_output=True, check=True)

    def prepare(self):
        if self.read('tracing_on') != '0':
            raise ValueError('cannot own trace clock while another trace is active')
        before = self.read('trace_clock')
        selected = re.findall(r'\[([a-z0-9_-]+)\]', before)
        buffer = self.read('buffer_size_kb')
        if len(selected) != 1 or not buffer.isdigit():
            raise ValueError('original trace clock/buffer configuration is ambiguous')
        self.old_clock, self.old_buffer = selected[0], buffer
        self.report.update(clockBefore=before, bufferSizeBeforeKb=buffer)
        self.write('trace_clock', 'mono\n')
        self.write('buffer_size_kb', '4096\n')
        self.configured_buffer = self.read('buffer_size_kb')
        if not self.configured_buffer.isdigit() or int(self.configured_buffer) < 4096:
            raise ValueError('kernel did not provision the requested trace buffer')
        self.report.update(bufferSizeRequestedKb=4096, bufferSizeConfiguredKb=self.configured_buffer)
        self.write('trace', '')
        self.observe('BeforeStart')

    def observe(self, phase):
        clock, buffer = self.read('trace_clock'), self.read('buffer_size_kb')
        self.report['clock' + phase] = clock
        self.report['bufferSize' + phase + 'Kb'] = buffer
        if re.findall(r'\[([^\]]+)\]', clock) != ['mono'] or buffer != self.configured_buffer:
            raise ValueError('owned raw trace clock/buffer changed')

    def restore(self, *, stopped):
        if self.restored or self.old_clock is None:
            return
        if stopped is not True or self.read('tracing_on') != '0':
            raise ValueError('trace remains active; clock restoration is pending')
        errors = []
        for name, value, field, result in [
            ('trace_clock', self.old_clock, 'clockRestored', 'restoredOriginalClock'),
            ('buffer_size_kb', self.old_buffer, 'bufferSizeRestoredKb', 'restoredOriginalBuffer'),
        ]:
            try:
                self.write(name, value + '\n')
                actual = self.read(name)
                self.report[field] = actual
                self.report[result] = (re.findall(r'\[([^\]]+)\]', actual) == [value]
                                       if name == 'trace_clock' else actual == value)
                if not self.report[result]:
                    errors.append(name + ' did not restore')
            except Exception as failure:
                errors.append(name + ': ' + str(failure))
        self.restored = not errors
        if errors:
            raise ValueError('; '.join(errors))


def raw_monotonic_config(config):
    if config.count(b'ftrace_config {') != 1 or b'preserve_ftrace_buffer:' in config or b'use_monotonic_raw_clock:' in config:
        raise ValueError('raw clock requires one unmodified ftrace configuration')
    return config.replace(b'ftrace_config {', b'ftrace_config {\n    preserve_ftrace_buffer: true\n    drain_period_ms: 50', 1)
