#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


DROP_TAGS = {"라노벨", "애니화", "만화", "웹툰"}

SPECIAL_LAST_RESORT = {
    "개같은 아저씨": ["드라마"],
    "아르카디아": ["판타지"],
    "KILLING ME / KI": ["판타지", "액션"],
    "타격계 오니 아가씨가 정복하": ["판타지", "액션"],
    "스파이 교실": ["스릴러", "액션", "라노벨"],
}


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def is_forced(item: dict[str, Any]) -> bool:
    classification = item.get("classification") if isinstance(item.get("classification"), dict) else {}
    flags = classification.get("flags") if isinstance(classification.get("flags"), dict) else {}
    return bool(flags.get("nonEmptyForced"))


def canonical_tags(taxonomy: dict[str, Any]) -> set[str]:
    tags: set[str] = set()
    for facet in ("genres", "relationship", "rating", "theme", "format"):
        tags.update(str(tag) for tag in taxonomy.get(facet, []))
    tags.update(str(alias) for alias in taxonomy.get("aliases", {}))
    return tags


def normalize_tags(values: list[Any], allowed: set[str], aliases: dict[str, str]) -> list[str]:
    result: list[str] = []
    for value in values:
        raw = str(value or "").strip()
        if not raw or raw in DROP_TAGS:
            continue
        tag = aliases.get(raw, raw)
        if tag not in allowed:
            continue
        if tag not in result:
            result.append(tag)
    return result[:4]


def build(db_path: Path, taxonomy_path: Path, output: Path) -> None:
    data = load_json(db_path)
    taxonomy = load_json(taxonomy_path)
    allowed = canonical_tags(taxonomy)
    aliases = {str(key): str(value) for key, value in taxonomy.get("aliases", {}).items()}
    rows: list[dict[str, Any]] = []
    titles = data.get("titles", {}) if isinstance(data.get("titles"), dict) else {}
    for key, item in titles.items():
        if not isinstance(item, dict) or not is_forced(item):
            continue
        source_tags = item.get("sourceTags") if isinstance(item.get("sourceTags"), list) else []
        tags = normalize_tags(source_tags, allowed, aliases)
        if not tags:
            tags = [tag for tag in SPECIAL_LAST_RESORT.get(str(item.get("name") or ""), []) if tag in allowed]
        if not tags:
            continue
        field = "ai.legacy_tags.last_resort" if source_tags and tags != SPECIAL_LAST_RESORT.get(str(item.get("name") or ""), []) else "ai.title.last_resort"
        rows.append(
            {
                "id": str(key),
                "source": "legacy-db-adjudication",
                "sourceFamily": "ai_adjudication",
                "field": field,
                "normalizedTags": tags,
                "identityScore": 1.0,
                "identitySignals": {"sameRecord": 1.0, "lastResortOnly": 1.0},
                "sourceReliability": 0.46,
                "fieldReliability": 0.56,
                "url": "",
                "basis": ["sourceTags", "same-db-record"] if field == "ai.legacy_tags.last_resort" else ["title", "known-work-pattern"],
                "note": "Used only after official, external, and description evidence failed; prevents generic forced placeholder.",
            }
        )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + ("\n" if rows else ""), encoding="utf-8")
    print(f"wrote={len(rows)}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build last-resort evidence from existing structured tags for unresolved records.")
    parser.add_argument("--db", required=True)
    parser.add_argument("--taxonomy", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    build(Path(args.db), Path(args.taxonomy), Path(args.output))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
