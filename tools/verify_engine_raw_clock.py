"""Check original kernel MONOTONIC/native clock brackets; no display or corpus credit."""
import hashlib
import re
from collections import Counter

from audit_engine_foreign_timeline import fields, require


def raw_prints(data):
    rows, clocks = [], Counter()
    for n, w, packet, _, _ in fields(data):
        require(n == 1 and w == 2, 'unsupported outer trace record')
        for pn, pw, bundle, _, _ in fields(packet):
            if pn != 1 or pw != 2:
                continue
            parts = list(fields(bundle))
            scalar = {k: v for k, kw, v, _, _ in parts if kw == 0}
            require(scalar.get(3, 0) == 0 and not any(k == 8 for k, _, _, _, _ in parts),
                    'original ftrace bundle reports loss or parse error')
            clocks[scalar.get(5, 0)] += 1
            for en, ew, event, _, _ in parts:
                if en != 2 or ew != 2:
                    continue
                event_fields = list(fields(event))
                header = {k: v for k, kw, v, _, _ in event_fields if kw == 0}
                for kind, wire, value, _, _ in event_fields:
                    if kind != 3 or wire != 2:
                        continue
                    payload = {k: v for k, _, v, _, _ in fields(value)}
                    text = payload.get(2, b'').decode('utf-8', errors='strict').rstrip('\n')
                    if '|viewer_clock' in text or '|viewer_swap:' in text:
                        require(header.get(1, 0) > 0 and header.get(2, 0) > 0,
                                'raw trace marker lacks time/thread identity')
                        rows.append({'ts': header[1], 'tid': header[2], 'text': text})
    return sorted(rows, key=lambda row: row['ts']), dict(clocks)


def verify_brackets(rows, expected_tokens, owner_pid):
    require(type(owner_pid) is int and owner_pid > 0 and expected_tokens and
            all(type(token) is int and token > 0 for token in expected_tokens) and
            len(expected_tokens) == len(set(expected_tokens)), 'invalid expected native token/owner set')
    starts, checks = {}, []
    for row in rows:
        begin = re.fullmatch(r'B\|([0-9]+)\|viewer_clock', row['text'])
        swap = re.fullmatch(r'B\|([0-9]+)\|viewer_swap:(-?[0-9]+):([0-9]+):([0-9]+)', row['text'])
        require(begin is not None or swap is not None, 'unsupported native clock marker')
        marker = begin or swap
        require(int(marker[1]) == owner_pid, 'raw clock marker belongs to another process')
        if begin:
            require(row['tid'] not in starts, 'duplicate native clock start')
            starts[row['tid']] = row
            continue
        start = starts.pop(row['tid'], None)
        require(start is not None, 'native clock start is missing')
        native = int(swap[4])
        require(start['ts'] <= native <= row['ts'], 'native clock is outside original kernel bracket')
        checks.append({'token': int(swap[2]), 'eglFrameId': int(swap[3]), 'tid': row['tid'],
                       'rawClockBegin': start['ts'], 'nativeMonotonicNs': native, 'rawSwapBegin': row['ts']})
    require(not starts, 'native clock marker suffix is incomplete')
    tokens = [row['token'] for row in checks if row['token'] > 0]
    require(tokens and len(tokens) == len(set(tokens)) and set(tokens) == set(expected_tokens),
            'native swap markers do not cover the complete submitted token set')
    require(len({row['tid'] for row in checks}) == 1, 'native swaps have multiple owner threads')
    return checks


def verify(data, collection, expected_tokens, owner_pid, trace_stats):
    require(collection.get('traceStopped') is True and collection.get('instrumentationSuccess') is True and
            collection.get('restoredOriginalClock') is True and collection.get('restoredOriginalBuffer') is True,
            'raw clock collection did not close and restore its owned configuration')
    require(hashlib.sha256(data).hexdigest() == collection['traceSha256'], 'original clock trace changed')
    for key in ('clockBeforeStart', 'clockDuring', 'clockBeforeStop'):
        require(re.findall(r'\[([^\]]+)\]', collection[key]) == ['mono'], 'tracefs MONOTONIC attestation is missing')
    require(isinstance(trace_stats, list) and not trace_stats,
            'trace import reports errors or data loss; raw clock control is incomplete')
    rows, clocks = raw_prints(data)
    checks = verify_brackets(rows, expected_tokens, owner_pid)
    return {'allRawMonotonicBracketsVerified': True, 'submittedSwapCount': len(expected_tokens),
            'declaredFtraceClockCounts': clocks, 'attestedTracefsClock': 'mono',
            'usesTraceProcessorConvertedTimestamps': False, 'checks': checks,
            'originalTraceSha256': collection['traceSha256'], 'physicalPresentationVerified': False,
            'corpusCredit': 0,
            'scope': 'Original raw kernel/native clock correspondence only; not Binder, buffer or display proof.'}
