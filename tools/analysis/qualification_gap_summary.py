# language: Python 3, file: qualification_gap_summary.py, runtime: stdlib only
# *Reads one EngineScrollQualification frames.jsonl once and prints submitted-vs-present gap stats.*
import json
import sys
from pathlib import Path


def percentile(sorted_values: list, p: float) -> float:
    index = min(int(len(sorted_values) * p), len(sorted_values) - 1)
    return sorted_values[index]


def main(path: str) -> None:
    rows = []
    with open(path, encoding="utf-8") as stream:
        for line in stream:
            if "DISPLAY_PRESENT" in line:
                rows.append(json.loads(line))
    submitted = [row["submittedAtNanos"] for row in rows]
    presented = [row["timestampNanos"] for row in rows]
    inputs = [row["inputRevision"] for row in rows]
    count = len(rows)
    latency = sorted((presented[i] - submitted[i]) / 1e6 for i in range(count))
    present_gap = sorted((presented[i] - presented[i - 1]) / 1e6 for i in range(1, count))
    submit_gap = sorted((submitted[i] - submitted[i - 1]) / 1e6 for i in range(1, count))
    pairs = count - 1
    stretch = sum(
        1 for i in range(1, count)
        if (presented[i] - presented[i - 1]) - (submitted[i] - submitted[i - 1]) > 8_000_000
    )
    scene = sum(1 for i in range(1, count) if submit_gap[i - 1] > 25.0)
    big = [i for i in range(1, count) if submitted[i] - submitted[i - 1] > 25_000_000]
    with_input = sum(1 for i in big if inputs[i] > inputs[i - 1])

    def line(label: str, values: list) -> str:
        return ("%-12s p50=%.1f p90=%.1f p95=%.1f p99=%.1f max=%.1f" % (
            line.__name__ if False else values and "" or "",
            0, 0, 0, 0, 0))

    def stats(values: list) -> str:
        return ("p50=%.1f p90=%.1f p95=%.1f p99=%.1f max=%.1f" % (
            percentile(values, 0.50), percentile(values, 0.90),
            percentile(values, 0.95), percentile(values, 0.99), values[-1]))

    print(f"frames={count} pairs={pairs}")
    print(f"submit_to_present_ms {stats(latency)}")
    print(f"present_gap_ms       {stats(present_gap)}")
    print(f"submit_gap_ms        {stats(submit_gap)}")
    print(f"gl_composite_stretch_pairs_gt_8ms={stretch} scene_cadence_gaps_gt_25ms={scene}")
    print(f"scene_gaps_gt_25ms_with_new_input={with_input}/{scene}")


if __name__ == "__main__":
    main(sys.argv[1])
