"""Decode original ATRACE/Binder events without TraceProcessor clock conversion."""
import re
from collections import defaultdict

from audit_engine_foreign_timeline import fields, require


def frame_events_from_trace(data, surfaceflinger_pid):
    names = {1: 'Dequeue', 2: 'Queue', 3: 'Post', 4: 'AcquireFenceSignaled', 5: 'Latch',
             6: 'HwcCompositionQueued', 7: 'FallbackComposition', 8: 'PresentFenceSignaled',
             9: 'ReleaseFenceSignaled', 10: 'Modify', 11: 'Detach', 12: 'Attach', 13: 'Cancel'}
    rows = []
    for ordinal, (n, w, packet, _, _) in enumerate(fields(data), 1):
        require(n == 1 and w == 2, 'unsupported outer trace record')
        parts = list(fields(packet))
        header = {k: v for k, kw, v, _, _ in parts if kw == 0}
        for kind, wire, body, _, _ in parts:
            if kind != 57 or wire != 2:
                continue
            require(header.get(58) == 3 and header.get(3) == 1000 and
                    header.get(79) == surfaceflinger_pid and header.get(8, 0) > 0,
                    'graphics event lacks trusted SurfaceFlinger/MONOTONIC identity')
            entries = list(fields(body))
            require(len(entries) == 1 and entries[0][:2] == (1, 2), 'unsupported graphics event payload')
            event = {k: v for k, _, v, _, _ in fields(entries[0][2])}
            require(event.get(2) in names and 1 in event and 3 in event,
                    'graphics event lacks supported type/frame/layer')
            rows.append({'id': ordinal, 'ts': header[8], 'name': names[event[2]],
                         'frame_number': event[1], 'layer_name': event[3].decode('utf-8', errors='strict'),
                         'dur': event.get(4, 0), 'graphics_buffer_id': event.get(5)})
    return rows


def events_from_trace(data):
    events = []
    for n, w, packet, _, _ in fields(data):
        require(n == 1 and w == 2, 'unsupported outer trace record')
        for pn, pw, bundle, _, _ in fields(packet):
            if pn != 1 or pw != 2:
                continue
            parts = list(fields(bundle))
            scalar = {k: v for k, kw, v, _, _ in parts if kw == 0}
            require(not scalar.get(3, 0) and not any(k == 8 for k, _, _, _, _ in parts),
                    'raw Binder trace has ftrace loss or parse errors')
            for en, ew, event, _, _ in parts:
                if en != 2 or ew != 2:
                    continue
                ef = list(fields(event))
                header = {k: v for k, kw, v, _, _ in ef if kw == 0}
                for kind, wire, body, _, _ in ef:
                    if wire != 2 or kind not in (3, 50, 51, 327):
                        continue
                    values = {k: v for k, _, v, _, _ in fields(body)}
                    record = {'ts': header[1], 'tid': header[2]}
                    if kind == 3:
                        text = values.get(2, b'').decode('utf-8', errors='strict').rstrip('\n')
                        begin = re.fullmatch(r'B\|([0-9]+)\|(.*)', text)
                        end = re.fullmatch(r'E(?:\|([0-9]+))?', text)
                        if begin:
                            record.update(kind='begin', marker_pid=int(begin[1]), name=begin[2])
                        elif end:
                            record.update(kind='end', marker_pid=int(end[1]) if end[1] else None)
                        else:
                            continue
                    elif kind == 50:
                        record.update(kind='send', binder_id=values[1], target_pid=values[3],
                                      reply=values.get(5, 0), flags=values.get(7, 0))
                    elif kind == 51:
                        record.update(kind='receive', binder_id=values[1])
                    else:
                        if values.get(1) != b'binder_transaction_buffer_release':
                            continue
                        identifiers = []
                        for k, kw, value, _, _ in fields(body):
                            if k == 2 and kw == 2:
                                item = {a: b for a, _, b, _, _ in fields(value)}
                                if item.get(1) == b'debug_id':
                                    require(4 in item, 'release lacks a signed debug ID field')
                                    identifiers.append(item[4])
                        require(len(identifiers) == 1, 'ambiguous raw release debug ID')
                        record.update(kind='release', binder_id=identifiers[0])
                    require(record['ts'] > 0 and record['tid'] > 0, 'invalid raw event identity/time')
                    events.append(record)
    return sorted(events, key=lambda event: event['ts'])


def normalize_events(events, thread_owners):
    """Thread owners are identity metadata only; every time comes from original events."""
    stacks = defaultdict(list)
    slices, releases, sends, receives = [], [], [], defaultdict(list)
    for ordinal, event in enumerate(events, 1):
        tid = event['tid']
        owner = thread_owners.get(tid, {})
        row = {'id': ordinal, 'ts': event['ts'], 'tid': tid, 'pid': owner.get('pid'),
               'uid': owner.get('uid'), 'process_name': owner.get('name'), 'dur': 0,
               'parent_id': stacks[tid][-1]['id'] if stacks[tid] else 0}
        kind = event['kind']
        if kind == 'begin':
            # An embedded ATRACE PID must never override kernel thread ownership.
            if event['marker_pid'] != row['pid']:
                row.update(pid=None, uid=None, process_name=None)
            row.update(name=event['name'], marker_pid=event['marker_pid'], dur=-1)
            stacks[tid].append(row)
            slices.append(row)
        elif kind == 'end':
            if not stacks[tid]:
                continue  # A span opened before recording is not a usable owned span.
            start = stacks[tid].pop()
            require(event.get('marker_pid') in (None, start['marker_pid']), 'raw ATRACE end owner changed')
            require(event['ts'] >= start['ts'], 'raw ATRACE span runs backwards')
            start['dur'] = event['ts'] - start['ts']
        elif kind == 'send':
            row.update(name='binder transaction async' if event['flags'] & 1 and not event['reply']
                       else 'binder transaction', binder_id=event['binder_id'], target_pid=event['target_pid'])
            slices.append(row)
            sends.append(row)
        elif kind == 'receive':
            row.update(name='binder async rcv', binder_id=event['binder_id'])
            slices.append(row)
            receives[event['binder_id']].append(row)
        elif kind == 'release':
            row.update(binder_id=event['binder_id'])
            releases.append(row)
        else:
            raise ValueError('unknown raw Binder event')
    flows = []
    for send in sends:
        if send['name'] != 'binder transaction async':
            continue
        for receive in receives[send['binder_id']]:
            if send['target_pid'] == receive['pid']:
                flows.append({'slice_out': send['id'], 'slice_in': receive['id']})
    return slices, flows, releases
