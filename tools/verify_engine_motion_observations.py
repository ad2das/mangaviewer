"""Audit complete application motion history; timestamps are not physical display proof."""
import argparse
import bisect
import json
import math
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_history(rows, windows, proof):
    for key in ('observationCount', 'windowCount', 'closedAtNanos', 'refreshPeriodNanos'):
        require(type(proof.get(key)) is int and proof[key] >= 0, f'invalid {key}')
    require(proof['refreshPeriodNanos'] > 0 and proof['closedAtNanos'] > 0, 'invalid clock')
    require(proof.get('historyOverwritten') is False, 'motion ring was overwritten')
    require(proof.get('physicalPresentationVerified') is False, 'motion is not display proof')
    require(len(rows) == proof['observationCount'], 'motion history is truncated')
    require(len(windows) == proof['windowCount'], 'gesture history is truncated')
    previous = 0
    for ordinal, row in enumerate(rows, 1):
        require(all(type(row.get(k)) is int for k in ('ordinal', 'sequence', 'frameTimeNanos', 'appliedAtNanos')),
                'invalid motion row')
        require(row['ordinal'] == ordinal and row['sequence'] > 0, 'motion ordinal has a gap')
        require(0 < row['frameTimeNanos'] <= row['appliedAtNanos'] <= proof['closedAtNanos'], 'invalid motion timestamp')
        require(row['appliedAtNanos'] >= previous, 'application clock moved backward')
        previous = row['appliedAtNanos']
    previous = 0
    for ordinal, window in enumerate(windows, 1):
        require(all(type(window.get(k)) is int for k in ('ordinal', 'startNanos', 'endNanos')), 'invalid gesture row')
        require(window['ordinal'] == ordinal, 'gesture ordinal has a gap')
        require(previous < window['startNanos'] <= window['endNanos'] <= proof['closedAtNanos'], 'invalid gesture interval')
        previous = window['endNanos']
    return True


def cadence(timestamps, windows, period, start, stop, membership_timestamps=None):
    """Keep both edges, including completely empty windows, so stalls cannot disappear."""
    require(period > 0 and 0 < start <= stop, 'invalid measurement interval')
    membership_timestamps = timestamps if membership_timestamps is None else membership_timestamps
    require(len(timestamps) == len(membership_timestamps), 'every cadence sample needs a membership timestamp')
    pairs = sorted(zip(membership_timestamps, timestamps))
    membership = [pair[0] for pair in pairs]
    intervals, edges = [], []
    samples = empty = count = 0
    for window in windows:
        left, right = max(start, window['startNanos']), min(stop, window['endNanos'])
        if left >= right:
            continue
        count += 1
        selected = pairs[bisect.bisect_left(membership, left):bisect.bisect_right(membership, right)]
        visible = sorted(set(pair[1] for pair in selected))
        samples += len(visible)
        if visible:
            intervals.extend(b-a for a, b in zip(visible, visible[1:]))
            edges.extend((selected[0][0]-left, right-selected[-1][0]))
        else:
            empty += 1
            edges.append(right-left)
    def missed(duration):
        return max(0, (duration + period//2)//period - 1)
    missing = sum(map(missed, intervals + edges))
    ordered = sorted(intervals)
    return dict(windowCount=count, emptyWindowCount=empty, sampleCount=samples,
        cadenceIntervalCount=len(intervals), missedFrameCount=missing,
        missedFrameRatio=missing/(len(intervals)+missing) if intervals or missing else None,
        p95IntervalMillis=ordered[math.ceil(len(ordered)*.95)-1]/1e6 if ordered else None,
        maxIntervalMillis=max(intervals, default=0)/1e6,
        maxEdgeMillis=max(edges, default=0)/1e6,
        gapsAtLeast100ms=sum(gap >= 100_000_000 for gap in intervals + edges))


def analyze(directory):
    def rows(name):
        return [json.loads(line) for line in (directory/name).read_text(encoding='utf-8').splitlines()]
    def obj(name):
        return json.loads((directory/name).read_bytes())
    motion, windows, proof = rows('motion.jsonl'), rows('motion-windows.jsonl'), obj('motion-close.json')
    verify_history(motion, windows, proof)
    summary, launch = obj('summary.json'), obj('ui-launch.json')
    result = dict(completeMotionHistoryVerified=True, physicalPresentationVerified=False,
        scope='Motion callback and successful native submission cadence during observed interaction windows. '
              'A callback can acknowledge deferred input; it is not proof of visible movement.',
        performanceQualified=False, corpusCredit=0)
    frames = rows('frames.jsonl')
    from verify_engine_frame_observations import verify_rows
    verify_rows(frames, obj('renderer-close.json'))
    callbacks = [r['appliedAtNanos'] for r in motion]
    submitted = [r['submittedAtNanos'] for r in frames if r['swapSucceeded']]
    # VSYNC precedes callback execution and may precede the gesture's start. Assign
    # it to the gesture where the callback ran, never to an earlier gesture.
    streams = dict(choreographer=([r['frameTimeNanos'] for r in motion], callbacks),
        motionCallback=(callbacks, callbacks), nativeSubmission=(submitted, submitted))
    for label, start in [('whole', launch['tapStartedMonotonicNs']),
                         ('afterFirstComplete', summary.get('firstCompleteViewportSubmittedAtNanos')),
                         ('prepared', summary.get('allFirstVerifiedPreparedAtNanos'))]:
        result[label] = None if start is None else {name: cadence(times, windows,
            proof['refreshPeriodNanos'], start, summary['stoppedAtNanos'], membership)
            for name, (times, membership) in streams.items()}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = analyze(args.directory)
    except (OSError, ValueError, TypeError, KeyError) as error:
        result = dict(completeMotionHistoryVerified=False, error=str(error), performanceQualified=False, corpusCredit=0)
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))
    raise SystemExit(0 if result['completeMotionHistoryVerified'] else 1)
