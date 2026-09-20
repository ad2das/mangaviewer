# language: Python 3, file: window_frame_summary.py, runtime: stdlib only
# *Reads one window-frames.jsonl and prints total-duration percentiles against the 60Hz budget.*
import json
import sys


def percentile(values: list, p: float) -> float:
    index = min(int(len(values) * p), len(values) - 1)
    return values[index]


def main(path: str) -> None:
    durations = []
    with open(path, encoding="utf-8") as stream:
        for line in stream:
            record = json.loads(line)
            duration = record.get("totalDurationNanos")
            if duration is not None and duration >= 0:
                durations.append(duration / 1e6)
    durations.sort()
    count = len(durations)
    budget = 16.7
    over = sum(1 for value in durations if value > budget)
    print(f"frames={count} over_budget={over} ({100.0 * over / max(count, 1):.1f}%)")
    print("total_duration_ms p50=%.1f p90=%.1f p95=%.1f p99=%.1f max=%.1f" % (
        percentile(durations, 0.50), percentile(durations, 0.90),
        percentile(durations, 0.95), percentile(durations, 0.99), durations[-1]))


if __name__ == "__main__":
    main(sys.argv[1])
