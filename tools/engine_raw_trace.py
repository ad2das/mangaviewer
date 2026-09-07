"""Load attested raw MONOTONIC viewer timing after complete journal/loss checks."""
import hashlib
import json
from collections import defaultdict

from audit_engine_foreign_timeline import require
from engine_raw_binder import events_from_trace, normalize_events, frame_events_from_trace
from verify_engine_frame_observations import verify_rows
from verify_engine_raw_clock import verify as verify_clock


def load_raw_rows(root, collection, metadata_slices, stats, audit, owner_pid):
    require(collection.get('rawMonotonicFtrace') is True, 'raw trace mode is not declared')
    names = collection['captureDirectories']
    require(len(names) == 1, 'raw trace capture directory is ambiguous')
    capture = root / names[0]
    journal_bytes = (capture / 'frames.jsonl').read_bytes()
    close_bytes = (capture / 'renderer-close.json').read_bytes()
    journal = [json.loads(line) for line in journal_bytes.decode('utf-8').splitlines()]
    history = verify_rows(journal, json.loads(close_bytes))
    require(history['completeRendererHistoryVerified'] is True, 'raw trace lacks complete native history')
    data = (root / 'display.perfetto-trace').read_bytes()
    if stats:
        require(audit is not None and audit.get('outOfEpisodeFrameEndErrorIsolated') is True and
                audit['originalTraceSha256'] == collection['traceSha256'] and audit['originalTraceStats'] == stats,
                'raw trace has unaudited import errors or data loss')
    proof = verify_clock(data, {**collection, **collection['rawMonotonicTrace']},
                         [row['token'] for row in journal], owner_pid, [])
    proof.update(journalSha256=hashlib.sha256(journal_bytes).hexdigest(),
                 closeProofSha256=hashlib.sha256(close_bytes).hexdigest())
    owners = defaultdict(set)
    for row in metadata_slices:
        owners[row['tid']].add((row['utid'], row['pid'], row['uid'], row['process_name']))
    unique = {}
    for tid, identities in owners.items():
        if len(identities) == 1:
            _, pid, uid, name = next(iter(identities))
            unique[tid] = dict(pid=pid, uid=uid, name=name)
    sf = {row['pid'] for row in unique.values() if row['name'] == '/system/bin/surfaceflinger' and row['uid'] == 1000}
    require(len(sf) == 1, 'raw trace lacks one trusted SurfaceFlinger process')
    rows, flows, releases = normalize_events(events_from_trace(data), unique)
    events = frame_events_from_trace(data, next(iter(sf)))
    (root / 'raw-clock-verification.json').write_text(json.dumps(proof, indent=2), encoding='utf-8')
    return rows, events, flows, releases, proof
