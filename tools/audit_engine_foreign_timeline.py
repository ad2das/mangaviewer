"""Isolate invalid non-viewer FrameEnds outside episode scope; keep the source trace unchanged."""
import hashlib
import json
from pathlib import Path
import re


def require(condition, message):
    if not condition:
        raise ValueError(message)


def varint(data, position):
    value = 0
    for shift in range(0, 70, 7):
        require(position < len(data), 'truncated protobuf varint')
        byte = data[position]
        position += 1
        require(shift < 63 or byte <= 1, 'protobuf varint overflow')
        value |= (byte & 127) << shift
        if byte < 128:
            return value, position
    raise ValueError('invalid protobuf varint')


def fields(data):
    position = 0
    while position < len(data):
        start = position
        tag, position = varint(data, position)
        number, wire = tag >> 3, tag & 7
        require(number > 0, 'invalid protobuf field number')
        if wire == 0:
            value, position = varint(data, position)
        elif wire in (1, 2, 5):
            if wire == 2:
                size, position = varint(data, position)
            else:
                size = 8 if wire == 1 else 4
            require(size <= len(data) - position, 'truncated protobuf field')
            value = data[position:position + size]
            position += size
        else:
            raise ValueError('unsupported protobuf wire type')
        yield number, wire, value, start, position


def candidates(data, owner_pid, owner_uid, processes, before_boot_ns=None):
    starts = {}
    ends = []
    for number, wire, packet, begin, finish in fields(data):
        require(number == 1 and wire == 2, 'trace does not contain ordinary packet records')
        packet_fields = list(fields(packet))
        scalar = {n: value for n, w, value, _, _ in packet_fields if w == 0}
        for n, w, timeline, _, _ in packet_fields:
            if n != 76 or w != 2:
                continue
            for kind, event_wire, event, _, _ in fields(timeline):
                if event_wire != 2 or kind not in (4, 5):
                    continue
                values = {n: value for n, _, value, _, _ in fields(event)}
                key = (scalar.get(79), scalar.get(10), values.get(1))
                if kind == 4:
                    require(key not in starts, 'ambiguous actual surface-frame cookie')
                    starts[key] = (values, scalar)
                elif scalar.get(8, 0) > (1 << 63) - 1_000_000:
                    ends.append((key, scalar, begin, finish))
    result = []
    for key, scalar, begin, finish in ends:
        require(key in starts, 'near-maximum FrameEnd has no exact actual-surface start')
        values, start_scalar = starts[key]
        pid = values.get(4)
        layer = values.get(5, b'').decode('utf-8', errors='strict')
        matching = [p for p in processes if p['pid'] == pid]
        prelaunch = before_boot_ns is not None and pid == owner_pid and re.fullmatch(
            r'TX - ml\.melun\.mangaview/ml\.melun\.mangaview\.activity\.MainActivity#[0-9]+', layer)
        status_bar = before_boot_ns is not None and pid != owner_pid and re.fullmatch(r'TX - StatusBar#[0-9]+', layer)
        if prelaunch:
            require(0 < start_scalar.get(8, 0) < before_boot_ns,
                    'MainActivity FrameEnd overlaps the episode launch interval')
            # ftrace may retain Android's 15-byte comm suffix instead of argv[0].
            # PID and package UID were independently bound to the native viewer above.
            require(len(matching) == 1 and matching[0]['name'] in ('ml.melun.mangaview', 'melun.mangaview') and
                    matching[0]['uid'] == owner_uid, 'prelaunch MainActivity process/UID is not established')
        elif status_bar:
            require(0 < start_scalar.get(8, 0) < before_boot_ns,
                    'StatusBar FrameEnd overlaps the episode launch interval')
            require(type(pid) is int and pid > 0 and len(matching) == 1 and
                    matching[0]['name'] == 'com.android.systemui' and
                    type(matching[0]['uid']) is int and matching[0]['uid'] > 0 and matching[0]['uid'] != owner_uid,
                    'prelaunch StatusBar process/UID is not independently established')
        else:
            require(type(pid) is int and pid > 0 and pid != owner_pid, 'invalid FrameEnd belongs to the viewer')
            require(re.fullmatch(r'TX - com\.google\.android\.apps\.nexuslauncher/com\.google\.android\.apps\.nexuslauncher\.NexusLauncherActivity#[0-9]+', layer),
                    'invalid FrameEnd is not the explicitly scoped foreign launcher layer')
            require(len(matching) == 1 and matching[0]['name'] == 'com.google.android.apps.nexuslauncher' and
                    type(matching[0]['uid']) is int and matching[0]['uid'] != owner_uid,
                    'foreign launcher process/UID is not independently established')
        sender = [p for p in processes if p['pid'] == scalar.get(79)]
        require(len(sender) == 1 and sender[0]['name'] == '/system/bin/surfaceflinger' and sender[0]['uid'] == 1000 and
                scalar.get(3) == start_scalar.get(3) == 1000 and scalar.get(58) == start_scalar.get(58) == 6,
                'FrameTimeline packet lacks the expected trusted SurfaceFlinger sender/clock')
        require(0 < start_scalar.get(8, 0) < scalar[8], 'invalid foreign frame start time')
        result.append({'startOffset': begin, 'endOffset': finish, 'cookie': key[2], 'pid': pid,
                       'uid': matching[0]['uid'], 'layer': layer, 'rawBoottimeTimestamp': scalar[8],
                       'scope': ('MAIN_ACTIVITY_BEFORE_EPISODE_TAP' if prelaunch else
                                 'STATUS_BAR_BEFORE_EPISODE_TAP' if status_bar else 'FOREIGN_LAUNCHER'),
                       'frameStartBoottimeNs': start_scalar[8],
                       'processName': matching[0]['name'],
                       'packetSha256': hashlib.sha256(data[begin:finish]).hexdigest()})
    return result


def audit(trace, stats, owner_pid, owner_uid, bin_path=None, tap_monotonic_ns=None):
    names = {row['name'] for row in stats}
    require(bool(stats) and len(names) == len(stats) and names <= {
                'trace_sorter_negative_timestamp_dropped', 'clock_sync_failure_no_path'} and
            all(type(row['value']) is int and row['value'] > 0 for row in stats),
            'trace has an unaudited error or data loss')
    clock_audit = None
    if 'clock_sync_failure_no_path' in names:
        from audit_engine_unused_tsc import audit as audit_clock
        clock_audit = audit_clock(trace, stats, bin_path)
    from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig
    config = TraceProcessorConfig(extra_flags=['--full-sort'], bin_path=bin_path)
    trace = Path(trace)
    with TraceProcessor(trace=str(trace), config=config) as processor:
        processes = [vars(row) for row in processor.query('SELECT pid,uid,name FROM process')]
    data = trace.read_bytes()
    before_boot_ns = None
    if tap_monotonic_ns is not None:
        require(type(tap_monotonic_ns) is int and tap_monotonic_ns > 0, 'invalid episode tap clock')
        offsets = []
        for _, _, packet, _, _ in fields(data):
            for n, w, snapshot, _, _ in fields(packet):
                if n != 6 or w != 2:
                    continue
                clocks = {}
                for cn, cw, clock, _, _ in fields(snapshot):
                    if cn == 1 and cw == 2:
                        values = {k: v for k, kw, v, _, _ in fields(clock) if kw == 0}
                        if values.get(1) in (3, 6):
                            require(values.get(3, 0) == 0 and values.get(4, 1) == 1,
                                    'unsupported scaled/incremental built-in clock')
                            clocks[values[1]] = values[2]
                if 3 in clocks and 6 in clocks:
                    offsets.append(clocks[3] - clocks[6])
        require(offsets, 'missing BOOTTIME/MONOTONIC snapshot binding')
        # Conservative bound across every snapshot, plus 1 ms uncertainty. Never
        # classify a frame near the tap using a favorable single clock sample.
        before_boot_ns = tap_monotonic_ns - max(offsets) - 1_000_000
    excluded = candidates(data, owner_pid, owner_uid, processes, before_boot_ns)
    negative_count = next((row['value'] for row in stats if row['name'] == 'trace_sorter_negative_timestamp_dropped'), 0)
    require(len(excluded) == negative_count, 'near-maximum out-of-episode packet count does not account for the error')
    pieces = []
    cursor = 0
    for entry in excluded:
        pieces.append(data[cursor:entry['startOffset']])
        cursor = entry['endOffset']
    pieces.append(data[cursor:])
    name = 'prelaunch-frame-end-audit' if tap_monotonic_ns is not None else 'foreign-frame-end-audit'
    diagnostic = trace.with_name('nonqualifying-' + name + '.perfetto-trace')
    diagnostic_data = b''.join(pieces)
    if clock_audit:
        from audit_engine_unused_tsc import remove_unused_tsc
        diagnostic_data, _ = remove_unused_tsc(diagnostic_data)
    diagnostic.write_bytes(diagnostic_data)
    with TraceProcessor(trace=str(diagnostic), config=config) as processor:
        remaining = [vars(row) for row in processor.query(
            "SELECT name,severity,value FROM stats WHERE value != 0 AND severity IN ('error','data_loss')")]
    require(not remaining, 'removing only foreign invalid FrameEnd packets does not eliminate the trace error')
    result = {'outOfEpisodeFrameEndErrorIsolated': True,
              'foreignLauncherFrameEndErrorIsolated': all(e['scope'] == 'FOREIGN_LAUNCHER' for e in excluded),
              'tapMonotonicNs': tap_monotonic_ns, 'conservativePrelaunchBoottimeCutoffNs': before_boot_ns,
              'originalTraceUnmodified': True,
              'unusedTscAudit': clock_audit,
              'qualifyingQueriesUseOriginalTrace': True, 'originalTraceSha256': hashlib.sha256(data).hexdigest(),
              'originalTraceStats': stats, 'outOfEpisodePackets': excluded,
              'nonqualifyingDiagnosticTrace': diagnostic.name,
              'nonqualifyingDiagnosticTraceSha256': hashlib.sha256(diagnostic.read_bytes()).hexdigest(),
              'diagnosticTraceErrorStats': remaining, 'physicalPresentationVerified': False, 'corpusCredit': 0}
    trace.with_name(name + '.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    return result
