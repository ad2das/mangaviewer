"""Audit unused TSC snapshot failures; never change event timestamps or the source trace."""
import hashlib
import json
from pathlib import Path

from audit_engine_foreign_timeline import fields, require


def encode(value):
    output = bytearray()
    while value >= 128:
        output.append((value & 127) | 128)
        value >>= 7
    output.append(value)
    return bytes(output)


def message(number, payload):
    return encode(number << 3 | 2) + encode(len(payload)) + payload


def remove_unused_tsc(data):
    # Scope is the collector's observed Android trace schema. Compressed packets,
    # track events/defaults, remote clocks and unknown payloads are deliberately rejected.
    allowed_messages = {1, 2, 6, 9, 33, 34, 35, 36, 45, 57, 69, 76, 89, 94}
    allowed_scalars = {3, 8, 10, 41, 42, 58, 79, 87}
    output = []
    previous = {}
    reversed_tsc = []
    snapshots = 0
    for n, w, packet, begin, end in fields(data):
        require(n == 1 and w == 2, 'unsupported outer trace record')
        packet_fields = list(fields(packet))
        require(all((w == 2 and n in allowed_messages) or (w == 0 and n in allowed_scalars)
                    for n, w, _, _, _ in packet_fields), 'unknown payload may use the TSC clock')
        scalars = {n: v for n, w, v, _, _ in packet_fields if w == 0}
        require(scalars.get(58, 6) in (3, 5, 6), 'packet uses an unaudited timestamp clock')
        pieces = []
        for n, w, value, a, b in packet_fields:
            if n == 1 and w == 2:
                ftrace = list(fields(value))
                fs = {k: v for k, kw, v, _, _ in ftrace if kw == 0}
                require(fs.get(5, 0) in (0, 4), 'ftrace clock is not BOOTTIME/MONOTONIC_RAW')
                require(fs.get(3, 0) == 0 and not any(k == 8 for k, _, _, _, _ in ftrace),
                        'ftrace reports loss or parse errors')
                if fs.get(5) == 4:
                    require(fs.get(6, 0) > 0 and fs.get(7, 0) > 0, 'ftrace clock pair is missing')
            if n != 6 or w != 2:
                pieces.append(packet[a:b])
                continue
            snapshot = list(fields(value))
            require(all(k == 1 and kw == 2 or k == 2 and kw == 0 for k, kw, _, _, _ in snapshot),
                    'unknown clock snapshot field')
            require(all(v in (3, 6) for k, kw, v, _, _ in snapshot if k == 2), 'TSC is the primary trace clock')
            clocks = {}
            kept = []
            for k, kw, clock, ca, cb in snapshot:
                if k != 1:
                    kept.append(value[ca:cb])
                    continue
                cf = list(fields(clock))
                require(all(cw == 0 and cn in (1, 2, 3, 4) for cn, cw, _, _, _ in cf), 'unknown clock definition')
                cs = {cn: cv for cn, cw, cv, _, _ in cf}
                clock_id = cs.get(1)
                require(clock_id in (1, 2, 3, 4, 5, 6, 9) and clock_id not in clocks,
                        'unknown or duplicate snapshot clock')
                require(cs.get(2, 0) > 0 and cs.get(3, 0) == 0 and cs.get(4, 1) == 1,
                        'invalid/incremental/scaled snapshot clock')
                clocks[clock_id] = cs[2]
                if clock_id != 9:
                    kept.append(value[ca:cb])
            require(all(k in clocks for k in (3, 5, 6, 9)), 'snapshot lacks core clock or TSC mapping')
            for k in (3, 5, 6):
                require(k not in previous or clocks[k] >= previous[k], 'used core clock moved backwards')
            if 9 in previous and clocks[9] < previous[9]:
                reversed_tsc.append({'packetOffset': begin, 'previousTsc': previous[9], 'tsc': clocks[9],
                                     'packetSha256': hashlib.sha256(data[begin:end]).hexdigest()})
            previous = clocks
            snapshots += 1
            pieces.append(message(6, b''.join(kept)))
        output.append(message(1, b''.join(pieces)))
    require(snapshots and reversed_tsc, 'no reversed unused TSC snapshot was established')
    return b''.join(output), {'snapshotCount': snapshots, 'reversedTscSnapshots': reversed_tsc,
                             'packetTimestampClocksRestrictedToCore': True, 'ftraceClockRestrictedToCore': True}


def audit(trace, stats, bin_path=None):
    from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig
    trace = Path(trace)
    raw = trace.read_bytes()
    diagnostic, evidence = remove_unused_tsc(raw)
    path = trace.with_name('nonqualifying-unused-tsc-audit.perfetto-trace')
    path.write_bytes(diagnostic)
    with TraceProcessor(trace=str(path), config=TraceProcessorConfig(extra_flags=['--full-sort'], bin_path=bin_path)) as processor:
        remaining = [vars(row) for row in processor.query(
            "SELECT name,severity,value FROM stats WHERE value != 0 AND severity IN ('error','data_loss')")]
    expected = [row for row in stats if row['name'] != 'clock_sync_failure_no_path']
    require(sorted(remaining, key=lambda r: r['name']) == sorted(expected, key=lambda r: r['name']),
            'unused TSC removal did not isolate exactly the clock synchronization errors')
    result = {**evidence, 'unusedTscClockErrorIsolated': True, 'originalTraceUnmodified': True,
              'originalTraceSha256': hashlib.sha256(raw).hexdigest(), 'originalTraceStats': stats,
              'diagnosticTraceSha256': hashlib.sha256(diagnostic).hexdigest(), 'diagnosticTraceErrorStats': remaining,
              'qualifyingQueriesUseOriginalTrace': True, 'physicalPresentationVerified': False, 'corpusCredit': 0}
    trace.with_name('unused-tsc-audit.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    return result
