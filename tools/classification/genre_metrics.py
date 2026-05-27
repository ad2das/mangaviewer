#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any


def load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def metrics(path: Path) -> dict[str, Any]:
    data = load(path)
    titles = data.get("titles", {}) if isinstance(data.get("titles"), dict) else {}
    total = len(titles)
    labels: Counter[str] = Counter()
    methods: Counter[str] = Counter()
    statuses: Counter[str] = Counter()
    forced = 0
    title_only = 0
    conflict = 0
    external_identity = 0
    external_evidence = 0
    empty = 0
    confidence_sum = 0.0
    for item in titles.values():
        if not isinstance(item, dict):
            continue
        resolved = item.get("resolvedTags") if isinstance(item.get("resolvedTags"), list) else []
        if not resolved:
            empty += 1
        classification = item.get("classification") if isinstance(item.get("classification"), dict) else {}
        flags = classification.get("flags") if isinstance(classification.get("flags"), dict) else {}
        labels[str(classification.get("confidenceLabel", "missing"))] += 1
        methods[str(classification.get("resolutionMethod", "missing"))] += 1
        statuses[str(classification.get("reviewStatus", "missing"))] += 1
        confidence = classification.get("confidence", 0.0)
        if isinstance(confidence, (int, float)):
            confidence_sum += float(confidence)
        if flags.get("nonEmptyForced"):
            forced += 1
        if flags.get("titleOnlyFallback"):
            title_only += 1
        if flags.get("sourceConflict"):
            conflict += 1
        if item.get("externalTags"):
            external_identity += 1
        if any(
            isinstance(entry, dict) and str(entry.get("sourceFamily", "")) in {"ai_adjudication", "search", "external_metadata", "official_platform"}
            for entry in (item.get("evidence") if isinstance(item.get("evidence"), list) else [])
        ):
            external_evidence += 1
    return {
        "path": str(path),
        "totalTitles": total,
        "nonEmptyRate": 0 if not total else round((total - empty) / total, 6),
        "emptyCount": empty,
        "avgConfidence": 0 if not total else round(confidence_sum / total, 4),
        "forcedFallbackRate": 0 if not total else round(forced / total, 6),
        "titleOnlyFallbackRate": 0 if not total else round(title_only / total, 6),
        "sourceConflictRate": 0 if not total else round(conflict / total, 6),
        "externalMatchRate": 0 if not total else round(external_identity / total, 6),
        "externalEvidenceRate": 0 if not total else round(external_evidence / total, 6),
        "confidenceLabels": dict(sorted(labels.items())),
        "resolutionMethods": dict(sorted(methods.items())),
        "reviewStatuses": dict(sorted(statuses.items())),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Report automated genre DB quality metrics.")
    parser.add_argument("paths", nargs="+")
    args = parser.parse_args()
    report = [metrics(Path(path)) for path in args.paths]
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
