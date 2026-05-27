#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def taxonomy_tags(taxonomy: dict[str, Any]) -> set[str]:
    tags: set[str] = set()
    for field in ("genres", "relationship", "rating", "theme", "format"):
        tags.update(str(tag) for tag in taxonomy.get(field, []))
    tags.update(str(key) for key in taxonomy.get("aliases", {}).keys())
    tags.update(str(value) for value in taxonomy.get("aliases", {}).values())
    return tags


def audit_db(path: Path, taxonomy: dict[str, Any], strict: bool) -> tuple[Counter[str], list[str]]:
    data = load_json(path)
    titles = data.get("titles", {}) if isinstance(data.get("titles"), dict) else {}
    allowed = taxonomy_tags(taxonomy)
    errors: list[str] = []
    counts: Counter[str] = Counter()
    for key, item in titles.items():
        if not isinstance(item, dict):
            continue
        resolved = item.get("resolvedTags")
        classification = item.get("classification") if isinstance(item.get("classification"), dict) else {}
        evidence = item.get("evidence")
        status = str(classification.get("reviewStatus", "missing"))
        counts[status] += 1
        if not isinstance(resolved, list):
            errors.append(f"{path}:{key}: missing resolvedTags")
            continue
        if len(resolved) == 0:
            errors.append(f"{path}:{key}: empty resolvedTags")
        for tag in resolved:
            if allowed and str(tag) not in allowed:
                errors.append(f"{path}:{key}: tag outside taxonomy: {tag}")
        if not isinstance(evidence, list) or not evidence:
            errors.append(f"{path}:{key}: missing evidence")
        if classification.get("resolvedTags") != resolved:
            errors.append(f"{path}:{key}: classification.resolvedTags differs from resolvedTags")
        confidence = classification.get("confidence")
        if not isinstance(confidence, (int, float)):
            errors.append(f"{path}:{key}: missing numeric confidence")
        flags = classification.get("flags")
        if not isinstance(flags, dict):
            errors.append(f"{path}:{key}: missing classification flags")
    return counts, errors


def check_assets(root: Path, errors: list[str]) -> None:
    pairs = [
        (root / "webtoon-classification.json", root / "app/src/main/assets/webtoon-classification.json"),
        (root / "comic-classification.json", root / "app/src/main/assets/comic-classification.json"),
    ]
    for left, right in pairs:
        if left.exists() and right.exists() and left.read_bytes() != right.read_bytes():
            errors.append(f"{left} and {right} differ")


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit resolved classification DBs.")
    parser.add_argument("--taxonomy", default="tools/classification/taxonomy.json")
    parser.add_argument("--webtoon", default="webtoon-classification.json")
    parser.add_argument("--comic", default="comic-classification.json")
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()

    root = Path.cwd()
    taxonomy = load_json(root / args.taxonomy)
    errors: list[str] = []
    for label, db_path in (("webtoon", root / args.webtoon), ("comic", root / args.comic)):
        counts, db_errors = audit_db(db_path, taxonomy, args.strict)
        errors.extend(db_errors)
        print(f"{label} statuses: {dict(sorted(counts.items()))}")
    check_assets(root, errors)
    print(f"errors: {len(errors)}")
    for error in errors[:200]:
        print(f"- {error}")
    if len(errors) > 200:
        print(f"- ... {len(errors) - 200} more")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
