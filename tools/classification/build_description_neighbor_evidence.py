#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


SENSITIVE = {"성인", "BL", "백합", "TS", "붕탁", "여장"}


def load_db(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def is_forced(item: dict[str, Any]) -> bool:
    classification = item.get("classification") if isinstance(item.get("classification"), dict) else {}
    flags = classification.get("flags") if isinstance(classification.get("flags"), dict) else {}
    return bool(flags.get("nonEmptyForced"))


def clean_text(value: str) -> str:
    text = re.sub(r"\s+", " ", value or "").strip()
    return text


def grams(text: str) -> set[str]:
    normalized = re.sub(r"\s+", "", text.casefold())
    result: set[str] = set()
    for n in (2, 3, 4):
        result.update(normalized[index : index + n] for index in range(max(0, len(normalized) - n + 1)))
    return {gram for gram in result if len(gram) >= 2}


def iter_titles(data: dict[str, Any]):
    titles = data.get("titles", {}) if isinstance(data.get("titles"), dict) else {}
    for key, item in titles.items():
        if isinstance(item, dict):
            yield str(key), item


def resolved_tags(item: dict[str, Any]) -> list[str]:
    classification = item.get("classification") if isinstance(item.get("classification"), dict) else {}
    tags = classification.get("resolvedTags") or item.get("resolvedTags") or item.get("canonicalTags") or []
    return [str(tag) for tag in tags if str(tag)]


def build_neighbor_evidence(db_path: Path, output: Path, min_score: float, max_neighbors: int) -> None:
    data = load_db(db_path)
    training: list[dict[str, Any]] = []
    targets: list[tuple[str, dict[str, Any], set[str]]] = []
    inverted: dict[str, list[int]] = defaultdict(list)

    for key, item in iter_titles(data):
        description = clean_text(str(item.get("description", "")))
        if len(description) < 12:
            continue
        gram_set = grams(description)
        if not gram_set:
            continue
        if is_forced(item):
            targets.append((key, item, gram_set))
            continue
        tags = [tag for tag in resolved_tags(item) if tag not in SENSITIVE]
        if not tags:
            continue
        training.append({"id": key, "description": description, "grams": gram_set, "tags": tags})
        index = len(training) - 1
        for gram in gram_set:
            inverted[gram].append(index)

    rows: list[dict[str, Any]] = []
    for key, item, target_grams in targets:
        overlaps: Counter[int] = Counter()
        for gram in target_grams:
            for index in inverted.get(gram, []):
                overlaps[index] += 1
        if not overlaps:
            continue
        scored: list[tuple[float, dict[str, Any]]] = []
        target_norm = math.sqrt(len(target_grams))
        for index, overlap in overlaps.items():
            neighbor = training[index]
            score = overlap / (target_norm * math.sqrt(len(neighbor["grams"])))
            if score >= min_score:
                scored.append((score, neighbor))
        if not scored:
            continue
        scored.sort(key=lambda pair: pair[0], reverse=True)
        top = scored[:max_neighbors]
        votes: Counter[str] = Counter()
        for score, neighbor in top:
            for tag in neighbor["tags"]:
                votes[tag] += max(1, int(round(score * 100)))
        tags = [tag for tag, _ in votes.most_common(4)]
        if not tags:
            continue
        best_score = top[0][0]
        rows.append(
            {
                "id": key,
                "source": "ai",
                "sourceFamily": "ai_adjudication",
                "field": "ai.synopsis.neighbor",
                "normalizedTags": tags,
                "identityScore": round(min(0.92, 0.58 + best_score), 4),
                "identitySignals": {
                    "descriptionNearestNeighbor": round(best_score, 4),
                    "neighborCount": len(top),
                },
                "sourceReliability": 0.58,
                "fieldReliability": 0.64,
                "url": "",
                "basis": ["description"],
                "neighbors": [
                    {
                        "id": neighbor["id"],
                        "score": round(score, 4),
                        "tags": neighbor["tags"],
                        "description": neighbor["description"][:180],
                    }
                    for score, neighbor in top[:3]
                ],
            }
        )

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + ("\n" if rows else ""), encoding="utf-8")
    print(f"training={len(training)} targets={len(targets)} wrote={len(rows)}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build description-only nearest-neighbor genre evidence.")
    parser.add_argument("--db", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--min-score", type=float, default=0.16)
    parser.add_argument("--max-neighbors", type=int, default=5)
    args = parser.parse_args()
    build_neighbor_evidence(Path(args.db), Path(args.output), args.min_score, args.max_neighbors)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
