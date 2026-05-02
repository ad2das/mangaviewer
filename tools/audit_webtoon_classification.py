#!/usr/bin/env python3
"""
Audit and apply high-confidence webtoon classification overrides.

This does not try to classify every title by title keywords. It only applies
overrides when the existing DB is missing a genre that is explicitly indicated
by source title text. The goal is to catch source-site genre omissions without
turning broad keyword guesses into runtime classification.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Dict, Iterable, List, Tuple


DB_FIELDS = ("manualTags", "externalTags", "sourceTags", "tags")

AUDIT_FALSE_POSITIVE_IDS = {
    7348,   # 조난! 에로로: source detail supports romance only.
    71787,  # 일기예보 살인마: source detail supports BL; thriller signal is title-only.
}


ADULT_RULES: List[Tuple[re.Pattern[str], str]] = [
    (re.compile(pattern), reason)
    for pattern, reason in [
        (r"19금|19세\s*완전판", "explicit 19+ title wording"),
        (r"섹스|섹파|바캉섹스|색드립", "explicit sexual title wording"),
        (r"음란|야한|에로|쾌감|발정", "explicit adult title wording"),
        (r"유부녀|처제|아내의\s*노출|몰카|노출교사", "adult relationship/title wording"),
        (r"마사지샵|마사지\s*파라다이스|과격해지는\s*마사지", "adult massage title wording"),
        (r"크림파이|AV(?!E|:)", "explicit adult-media title wording"),
    ]
]

BL_RULES: List[Tuple[re.Pattern[str], str]] = [
    (re.compile(pattern, re.IGNORECASE), reason)
    for pattern, reason in [
        (r"\bBL\b|비엘", "explicit BL title wording"),
        (r"오메가버스|감금\s*BL|BL단편선", "explicit BL subgenre wording"),
    ]
]

MARTIAL_RULES: List[Tuple[re.Pattern[str], str]] = [
    (re.compile(pattern), reason)
    for pattern, reason in [
        (r"무림|마교|화산파|화산전생|남궁세가", "explicit martial-world title wording"),
        (r"검왕|검신|마검왕", "explicit martial title wording"),
        (r"(?<!온)천마(?!디)", "explicit 천마 title wording"),
    ]
]

HORROR_RULES: List[Tuple[re.Pattern[str], str]] = [
    (re.compile(pattern), reason)
    for pattern, reason in [
        (r"괴담|흉가|공포게임|괴담학교", "explicit horror title wording"),
        (r"살인마|살인자와의\s*인터뷰|살인마의\s*인터뷰", "explicit thriller title wording"),
    ]
]


def unique(values: Iterable[str]) -> List[str]:
    result: List[str] = []
    for value in values:
        if value and value not in result:
            result.append(value)
    return result


def current_tags(item: Dict[str, object]) -> List[str]:
    tags: List[str] = []
    for field in DB_FIELDS:
        values = item.get(field)
        if isinstance(values, list):
            tags.extend(str(value) for value in values if value)
    return unique(tags)


def add_override(
    item: Dict[str, object],
    required: Iterable[str],
    reason: str,
    preserve_existing: bool,
) -> bool:
    current = current_tags(item)
    required_tags = unique(required)
    if all(tag in current for tag in required_tags):
        return False

    base = unique(required_tags + (current if preserve_existing else []))
    item["manual"] = True
    item["manualTags"] = base
    item["manualSource"] = reason
    item["tags"] = base
    return True


def matching_reasons(title: str, rules: List[Tuple[re.Pattern[str], str]]) -> List[str]:
    reasons = []
    for pattern, reason in rules:
        if pattern.search(title):
            reasons.append(reason)
    return unique(reasons)


def audit_title(title: str, tags: List[str]) -> List[Tuple[List[str], str, bool]]:
    changes: List[Tuple[List[str], str, bool]] = []

    adult_reasons = matching_reasons(title, ADULT_RULES)
    if adult_reasons and "성인" not in tags:
        changes.append((["성인"], "; ".join(adult_reasons), True))

    bl_reasons = matching_reasons(title, BL_RULES)
    if bl_reasons and "BL" not in tags:
        changes.append((["BL"], "; ".join(bl_reasons), True))

    martial_reasons = matching_reasons(title, MARTIAL_RULES)
    if martial_reasons and "무협" not in tags:
        changes.append((["무협"], "; ".join(martial_reasons), True))

    horror_reasons = matching_reasons(title, HORROR_RULES)
    if horror_reasons and not any(tag in tags for tag in ("공포", "스릴러", "미스터리")):
        first_reason = horror_reasons[0]
        genre = "스릴러" if "thriller" in first_reason or "살인" in title else "공포"
        changes.append(([genre], "; ".join(horror_reasons), True))

    return changes


def audit(data: Dict[str, object], apply: bool) -> List[Dict[str, object]]:
    titles = data.get("titles", {})
    if not isinstance(titles, dict):
        return []

    report: List[Dict[str, object]] = []
    for key, item in titles.items():
        if not isinstance(item, dict):
            continue
        try:
            title_id = int(key)
        except ValueError:
            title_id = 0
        if title_id in AUDIT_FALSE_POSITIVE_IDS:
            continue
        title = str(item.get("name", ""))
        tags = current_tags(item)
        changes = audit_title(title, tags)
        if not changes:
            continue

        suggested = tags[:]
        reasons: List[str] = []
        for required, reason, preserve_existing in changes:
            suggested = unique(required + suggested if preserve_existing else required)
            reasons.append(reason)
            if apply:
                add_override(
                    item,
                    required,
                    "automated audit override: " + reason,
                    preserve_existing=preserve_existing,
                )
                tags = current_tags(item)

        report.append(
            {
                "id": int(key),
                "name": title,
                "before": tags if apply else current_tags(item),
                "suggested": suggested,
                "reasons": reasons,
            }
        )
    return report


def load_json(path: Path) -> Dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Dict[str, object]) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit webtoon classification DB.")
    parser.add_argument("--db", default="webtoon-classification.json")
    parser.add_argument("--asset-db", default="app/src/main/assets/webtoon-classification.json")
    parser.add_argument("--report", default="webtoon-classification-audit.json")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    db_path = Path(args.db)
    data = load_json(db_path)
    report = audit(data, apply=args.apply)
    Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if args.apply:
        write_json(db_path, data)
        asset_path = Path(args.asset_db)
        if asset_path.exists():
            write_json(asset_path, data)

    print(f"report: {len(report)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
