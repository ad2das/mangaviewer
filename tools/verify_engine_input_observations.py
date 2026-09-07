"""Check actual receipt history without treating it as pixel or presentation proof."""
import argparse
import hashlib
import json
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_rows(rows, close_proof=None):
    require(bool(rows) or close_proof is not None, 'input history is empty')
    sequences = {}
    sessions = set()
    revision = 0
    for ordinal, row in enumerate(rows, 1):
        for name in ('ordinal', 'sessionId', 'generation', 'inputRevision', 'geometryRevision', 'pendingInputCount',
                     'sequence', 'gestureId', 'eventTimeNanos', 'deltaScreenUnits', 'acceptedAtNanos',
                     'appliedScreenUnits', 'receiptGeometryRevision'):
            require(type(row.get(name)) is int, f'{name} is not an integer')
        require(row['ordinal'] == ordinal, 'missing or duplicated receipt ordinal')
        sessions.add(row['sessionId'])
        require(row['sessionId'] > 0 and row['generation'] > 0 and row['gestureId'] > 0, 'invalid receipt identity')
        sequence = row['sequence']
        require(0 < sequence <= row['inputRevision'] and row['inputRevision'] >= revision, 'input revision is inconsistent')
        revision = row['inputRevision']
        require(row['geometryRevision'] >= row['receiptGeometryRevision'] >= 0 and row['pendingInputCount'] >= 0,
                'invalid receipt geometry or pending count')
        require(0 <= row['eventTimeNanos'] <= row['acceptedAtNanos'], 'input acceptance precedes event')
        outcome = row['outcome']
        require(outcome in ('DEFERRED', 'APPLIED', 'CLAMPED', 'CANCELLED'), 'unknown input outcome')
        resolved = row['resolvedAtNanos']
        require((outcome == 'DEFERRED' and resolved is None) or
                (outcome != 'DEFERRED' and type(resolved) is int and resolved >= row['acceptedAtNanos']),
                'receipt resolution time is invalid')
        delta, applied = row['deltaScreenUnits'], row['appliedScreenUnits']
        require(min(0, delta) <= applied <= max(0, delta), 'applied distance exceeds requested input')
        if outcome == 'APPLIED':
            require(applied == delta, 'applied input lost distance')
        boundary = row['boundary']
        require((outcome == 'CLAMPED') == (boundary is not None), 'clamp has no exclusive boundary proof')
        if boundary is not None:
            require(boundary['kind'] in ('START', 'END') and
                    boundary['geometryRevision'] == row['receiptGeometryRevision'], 'invalid boundary proof')
            identity = boundary['pageIdentity']
            require(all(isinstance(identity.get(k), str) and identity[k] for k in
                        ('sourceId', 'seriesKey', 'episodeKey', 'pageKey')), 'boundary page identity is incomplete')
        prior = sequences.get(sequence)
        if prior is None:
            require(sequence == len(sequences) + 1, 'accepted input sequence has a gap or reordering')
        else:
            require(prior['outcome'] == 'DEFERRED', 'input has more than one terminal receipt')
            require(all(prior[k] == row[k] for k in
                        ('sessionId', 'sequence', 'gestureId', 'eventTimeNanos', 'deltaScreenUnits', 'acceptedAtNanos')),
                    'replayed input identity changed')
            require(abs(applied) >= abs(prior['appliedScreenUnits']), 'deferred input lost already applied distance')
        sequences[sequence] = row
    require(len(sessions) == 1 or not rows, 'input history mixes sessions')
    require(len(sequences) == revision, 'missing accepted inputs at final revision')
    require(all(row['outcome'] != 'DEFERRED' for row in sequences.values()), 'input is unresolved after close')
    if close_proof is not None:
        for key in ('sessionId', 'generation', 'inputRevision', 'receivedInputCount', 'observationCount', 'closedAtNanos'):
            require(type(close_proof.get(key)) is int, f'close proof {key} is not an integer')
        require(close_proof['sessionId'] > 0 and close_proof['generation'] > 0 and close_proof['closedAtNanos'] > 0,
                'close proof identity/time is invalid')
        require(close_proof['observationCount'] == len(rows), 'receipt suffix is missing from sealed history')
        require(close_proof['receivedInputCount'] == close_proof['inputRevision'] == revision == len(sequences),
                'closed session/input count disagrees with history')
        require(all(row['sessionId'] == close_proof['sessionId'] and row['generation'] <= close_proof['generation'] and
                    (row['resolvedAtNanos'] or row['acceptedAtNanos']) <= close_proof['closedAtNanos'] for row in rows),
                'receipt lies outside the closed session')
    return {'inputHistoryVerified': True, 'observationCount': len(rows), 'acceptedInputCount': len(sequences),
            'completeSessionInputHistoryVerified': close_proof is not None,
            'scope': 'Engine adapter input receipts; raw MotionEvent-to-adapter correspondence is separate.',
            'deferredObservationCount': sum(row['outcome'] == 'DEFERRED' for row in rows),
            'cancelledInputCount': sum(row['outcome'] == 'CANCELLED' for row in sequences.values()),
            'clampedInputCount': sum(row['outcome'] == 'CLAMPED' for row in sequences.values()),
            'physicalPresentationVerified': False, 'wholeEpisodeVerified': False, 'corpusCredit': 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--close-proof', type=Path)
    args = parser.parse_args()
    raw = args.input.read_bytes()
    try:
        proof_raw = args.close_proof.read_bytes() if args.close_proof else None
        result = verify_rows([json.loads(line) for line in raw.decode('utf-8').splitlines()],
            json.loads(proof_raw) if proof_raw is not None else None)
        if proof_raw is not None:
            result['closeProofSha256'] = hashlib.sha256(proof_raw).hexdigest()
    except (ValueError, KeyError, TypeError) as failure:
        result = {'inputHistoryVerified': False, 'error': str(failure), 'corpusCredit': 0}
    result['inputSha256'] = hashlib.sha256(raw).hexdigest()
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))
    return 0 if result['inputHistoryVerified'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
