#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Iterable


DB_FIELDS = ("manualTags", "externalTags", "sourceTags", "tags", "inferredTags")
UNCLASSIFIED = "\ubbf8\ubd84\ub958"


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def unique(values: Iterable[str]) -> list[str]:
    result: list[str] = []
    for value in values:
        if value and value not in result:
            result.append(value)
    return result


def item_tags(item: dict) -> list[str]:
    resolved = item.get("resolvedTags")
    if isinstance(resolved, list):
        return [tag for tag in unique(str(value).strip() for value in resolved if str(value).strip()) if tag != UNCLASSIFIED]
    classification = item.get("classification")
    if isinstance(classification, dict):
        nested = classification.get("resolvedTags")
        if isinstance(nested, list):
            return [tag for tag in unique(str(value).strip() for value in nested if str(value).strip()) if tag != UNCLASSIFIED]

    tags: list[str] = []
    for field in DB_FIELDS:
        values = item.get(field)
        if isinstance(values, list):
            tags.extend(str(value).strip() for value in values if str(value).strip())
    return [tag for tag in unique(tags) if tag != UNCLASSIFIED]


def count_db_tags(path: Path) -> Counter[str]:
    data = load_json(path)
    counts: Counter[str] = Counter()
    for item in data.get("titles", {}).values():
        if isinstance(item, dict):
            counts.update(item_tags(item))
    return counts


def extract_java_string_array(source: str, name: str) -> list[str]:
    pattern = rf"private\s+static\s+final\s+String\[\]\s+{re.escape(name)}\s*=\s*\{{(.*?)\}};"
    match = re.search(pattern, source, re.S)
    if not match:
        raise ValueError(f"Could not find {name}")
    return re.findall(r'"((?:\\.|[^"\\])*)"', match.group(1))


def check_assets_match(root: Path, asset: Path, errors: list[str]) -> None:
    if root.read_bytes() != asset.read_bytes():
        errors.append(f"{root} and {asset} differ")


def check_filters(name: str, counts: Counter[str], filters: list[str], errors: list[str]) -> None:
    filter_set = {value for value in filters if value != UNCLASSIFIED}
    missing = sorted(tag for tag in counts if tag not in filter_set)
    if missing:
        errors.append(f"{name} DB tags missing from filters: {', '.join(missing)}")


def check_paged_counts(name: str, counts: Counter[str], page_size: int, errors: list[str]) -> None:
    for tag, count in counts.items():
        paged_count = 0
        offset = 0
        while offset < count:
            page_count = min(page_size, count - offset)
            paged_count += page_count
            offset += page_count
        if paged_count != count:
            errors.append(f"{name} {tag}: paged {paged_count}, expected {count}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit genre result coverage.")
    parser.add_argument("--page-size", type=int, default=120)
    args = parser.parse_args()

    root = Path.cwd()
    java_source = (root / "app/src/main/java/ml/melun/mangaview/mangaview/MainPageWebtoon.java").read_text(encoding="utf-8")

    webtoon_db = root / "webtoon-classification.json"
    comic_db = root / "comic-classification.json"
    webtoon_asset = root / "app/src/main/assets/webtoon-classification.json"
    comic_asset = root / "app/src/main/assets/comic-classification.json"

    errors: list[str] = []
    check_assets_match(webtoon_db, webtoon_asset, errors)
    check_assets_match(comic_db, comic_asset, errors)

    webtoon_counts = count_db_tags(webtoon_db)
    comic_counts = count_db_tags(comic_db)
    webtoon_filters = extract_java_string_array(java_source, "WEBTOON_GENRES")
    comic_filters = extract_java_string_array(java_source, "COMIC_GENRES")

    check_filters("webtoon", webtoon_counts, webtoon_filters, errors)
    check_filters("comic", comic_counts, comic_filters, errors)
    check_paged_counts("webtoon", webtoon_counts, args.page_size, errors)
    check_paged_counts("comic", comic_counts, args.page_size, errors)

    print(f"webtoon genres: {len(webtoon_counts)}, titles by tag: {sum(webtoon_counts.values())}")
    print(f"comic genres: {len(comic_counts)}, titles by tag: {sum(comic_counts.values())}")
    print(f"errors: {len(errors)}")
    for error in errors:
        print(f"- {error}")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
