"""Verify complete renderer delivery history and report native submission costs, not display cadence."""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_rows(rows, proof):
    for key in ('rendererId', 'submittedFrameCount', 'deliveredObservationCount', 'closedAtNanos'):
        require(type(proof.get(key)) is int, f'close proof {key} is not an integer')
    require(proof['rendererId'] > 0 and proof['closedAtNanos'] > 0 and proof['submittedFrameCount'] >= 0, 'invalid renderer close proof')
    require(len(rows) == proof['submittedFrameCount'] == proof['deliveredObservationCount'], 'renderer history is truncated or undelivered')
    tokens = set()
    positive_kinds = {'DISPLAY_PRESENT', 'COMPOSITION_LATCH', 'RENDERING_COMPLETE', 'SWAP_RETURN'}
    kinds = positive_kinds | {'UNAVAILABLE', 'CANCELLED', 'DROPPED', 'CONTEXT_LOST'}
    for ordinal, row in enumerate(rows, 1):
        for key in ('ordinal', 'rendererId', 'sessionId', 'rendererEpoch', 'surfaceEpoch', 'token', 'inputRevision',
                    'geometryRevision', 'submittedAtNanos', 'renderSubmissionDurationNanos', 'timestampNanos',
                    'visiblePlacementCount', 'eglFrameId'):
            require(type(row.get(key)) is int, f'frame {key} is not an integer')
        require(row['ordinal'] == ordinal, 'frame observation ordinal has a gap')
        require(row['rendererId'] == proof['rendererId'] and all(row[k] > 0 for k in
                ('sessionId', 'rendererEpoch', 'surfaceEpoch', 'token')), 'frame belongs to another renderer or invalid epoch')
        require(row['token'] not in tokens, 'native frame token is duplicated')
        tokens.add(row['token'])
        require(row['inputRevision'] >= 0 and row['geometryRevision'] >= 0 and row['visiblePlacementCount'] >= 0 and
                row['eglFrameId'] >= 0, 'frame revision/count is invalid')
        require(type(row.get('swapSucceeded')) is bool and type(row.get('completeViewportCoverage')) is bool,
                'frame result is not a boolean')
        start, cost = row['submittedAtNanos'], row['renderSubmissionDurationNanos']
        require(0 < start <= start + cost <= proof['closedAtNanos'], 'native submission interval is invalid')
        kind, timestamp = row['timestampKind'], row['timestampNanos']
        require(kind in kinds and timestamp >= 0, 'invalid timestamp kind/value')
        require(kind not in positive_kinds or start <= timestamp <= proof['closedAtNanos'], 'frame timestamp is outside its lifetime')
        require(row.get('physicalPresentationVerified') is False, 'unverified frame history claims physical display')
    require(tokens == set(range(1, len(rows) + 1)), 'native submission tokens have a gap')
    costs = sorted(row['renderSubmissionDurationNanos'] for row in rows)
    ordered = sorted(rows, key=lambda row: row['token'])
    require(all(a['submittedAtNanos'] <= b['submittedAtNanos'] for a, b in zip(ordered, ordered[1:])),
            'submission clock moved backward')
    return {'completeRendererHistoryVerified': True, 'submittedFrameCount': len(rows),
        'nativeSubmissionP95Millis': costs[math.ceil(len(costs) * .95) - 1] / 1e6 if costs else None,
        'nativeSubmissionMaxMillis': costs[-1] / 1e6 if costs else None,
        'nativeSubmissionsAtLeast100ms': sum(cost >= 100_000_000 for cost in costs),
        'failedSwapCount': sum(not row['swapSucceeded'] for row in rows),
        'zeroSourceSceneCount': sum(row['visiblePlacementCount'] == 0 for row in rows),
        'incompleteViewportSceneCount': sum(not row['completeViewportCoverage'] for row in rows),
        'timestampKinds': dict(Counter(row['timestampKind'] for row in rows)),
        'physicalPresentationVerified': False, 'missedDisplayFrameRate': None, 'performanceQualified': False,
        'wholeEpisodeVerified': False, 'corpusCredit': 0,
        'scope': 'Native submission call duration and delivered scene metadata; not end-to-end input/display latency or pixel proof.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--frames', type=Path, required=True)
    parser.add_argument('--close-proof', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        raw, proof = args.frames.read_bytes(), args.close_proof.read_bytes()
        result = verify_rows([json.loads(line) for line in raw.decode().splitlines()], json.loads(proof))
        result.update(framesSha256=hashlib.sha256(raw).hexdigest(), closeProofSha256=hashlib.sha256(proof).hexdigest())
    except (OSError, ValueError, KeyError, TypeError) as failure:
        result = {'completeRendererHistoryVerified': False, 'error': str(failure), 'corpusCredit': 0}
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))
    raise SystemExit(0 if result['completeRendererHistoryVerified'] else 1)
