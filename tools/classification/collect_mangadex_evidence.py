#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from threading import Lock
from typing import Any


MANGADEX_TAG_MAP = {
    "Action": ["액션"],
    "Adventure": ["판타지"],
    "Award Winning": [],
    "Comedy": ["개그"],
    "Demons": ["판타지"],
    "Drama": ["드라마"],
    "Fantasy": ["판타지"],
    "Gore": ["공포"],
    "Historical": ["역사", "시대"],
    "Horror": ["공포"],
    "Isekai": ["이세계", "판타지"],
    "Magic": ["판타지"],
    "Martial Arts": ["액션", "무협"],
    "Mecha": ["SF", "액션"],
    "Medical": ["드라마"],
    "Mystery": ["미스터리"],
    "Office Workers": ["일상", "드라마"],
    "Official Colored": [],
    "Philosophical": ["드라마"],
    "Psychological": ["스릴러"],
    "Reincarnation": ["전생", "판타지"],
    "Romance": ["로맨스"],
    "Sci-Fi": ["SF"],
    "School Life": ["학원"],
    "Sexual Violence": ["성인"],
    "Shoujo": ["순정"],
    "Shounen": ["액션"],
    "Slice of Life": ["일상"],
    "Sports": ["스포츠"],
    "Supernatural": ["판타지"],
    "Thriller": ["스릴러"],
    "Tragedy": ["드라마"],
    "Video Games": ["게임"],
    "Villainess": ["판타지"],
    "Virtual Reality": ["게임", "SF"],
    "Yuri": ["백합"],
    "Boys' Love": ["BL"],
    "Girls' Love": ["백합"],
}


def normalize_title(value: str) -> str:
    text = re.sub(r"\([^)]*\)", "", value or "")
    return re.sub(r"[\W_]+", "", text, flags=re.UNICODE).casefold()


def load_db(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def iter_titles(data: dict[str, Any]) -> list[tuple[str, dict[str, Any]]]:
    titles = data.get("titles", {}) if isinstance(data.get("titles"), dict) else {}
    return [(str(key), item) for key, item in titles.items() if isinstance(item, dict)]


def needs_external(item: dict[str, Any], only_forced: bool, only_missing_description: bool) -> bool:
    if only_missing_description and str(item.get("description", "")).strip():
        return False
    if only_forced:
        classification = item.get("classification") if isinstance(item.get("classification"), dict) else {}
        flags = classification.get("flags") if isinstance(classification.get("flags"), dict) else {}
        return bool(flags.get("nonEmptyForced"))
    return True


def load_existing_output(path: Path) -> tuple[list[dict[str, Any]], set[str]]:
    rows: list[dict[str, Any]] = []
    seen: set[str] = set()
    if not path.exists():
        return rows, seen
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(row, dict):
            continue
        title_id = str(row.get("id", ""))
        if title_id:
            seen.add(title_id)
        rows.append(row)
    return rows, seen


def load_cache(path: Path | None) -> dict[str, Any]:
    if not path or not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def save_cache(path: Path | None, cache: dict[str, Any]) -> None:
    if not path:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(cache, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def unique(values: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        if not value:
            continue
        key = value.casefold()
        if key in seen:
            continue
        seen.add(key)
        result.append(value)
    return result


def map_tags(raw_tags: list[str]) -> list[str]:
    mapped: list[str] = []
    for tag in raw_tags:
        mapped.extend(MANGADEX_TAG_MAP.get(tag, []))
    return unique(mapped)


def request_json(url: str, timeout: float) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "mangaviewer-genre-audit/1.0",
            "Accept": "application/json",
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8", errors="replace"))


def title_values(attributes: dict[str, Any]) -> list[str]:
    values: list[str] = []
    title = attributes.get("title")
    if isinstance(title, dict):
        values.extend(str(value) for value in title.values() if value)
    alt_titles = attributes.get("altTitles")
    if isinstance(alt_titles, list):
        for alt in alt_titles:
            if isinstance(alt, dict):
                values.extend(str(value) for value in alt.values() if value)
    return values


def fetch_one(
    title_id: str,
    item: dict[str, Any],
    timeout: float,
    delay: float,
    cache: dict[str, Any] | None = None,
    cache_lock: Lock | None = None,
) -> dict[str, Any] | None:
    title = str(item.get("name", "")).strip()
    key = normalize_title(title)
    if len(key) < 4:
        return None
    if cache is not None:
        cached = cache.get(key)
        if isinstance(cached, dict):
            row = cached.get("row")
            if isinstance(row, dict):
                next_row = dict(row)
                next_row["id"] = title_id
                next_row.setdefault("identitySignals", {})["cacheHit"] = 1.0
                return next_row
            if cached.get("miss"):
                return None
    if delay:
        time.sleep(delay)
    url = "https://api.mangadex.org/manga?" + urllib.parse.urlencode(
        {
            "title": title,
            "limit": "3",
        }
    )
    data: dict[str, Any] | None = None
    for attempt in range(3):
        try:
            data = request_json(url, timeout)
            break
        except Exception:
            if attempt < 2:
                time.sleep(0.5 * (attempt + 1))
    if data is None:
        return None
    results = data.get("data")
    if not isinstance(results, list) or not results:
        if cache is not None:
            with cache_lock or Lock():
                cache[key] = {"miss": True}
        return None
    best = results[0]
    attributes = best.get("attributes") if isinstance(best, dict) else {}
    if not isinstance(attributes, dict):
        if cache is not None:
            with cache_lock or Lock():
                cache[key] = {"miss": True}
        return None
    raw_tags = []
    for tag in attributes.get("tags", []) if isinstance(attributes.get("tags"), list) else []:
        tag_attrs = tag.get("attributes") if isinstance(tag, dict) else {}
        name = tag_attrs.get("name") if isinstance(tag_attrs, dict) else {}
        if isinstance(name, dict) and name.get("en"):
            raw_tags.append(str(name["en"]))
    tags = map_tags(raw_tags)
    if not tags:
        if cache is not None:
            with cache_lock or Lock():
                cache[key] = {"miss": True, "rawTags": raw_tags}
        return None
    values = title_values(attributes)
    normalized_values = [normalize_title(value) for value in values]
    exact_title_seen = key in normalized_values
    identity_score = 0.88 if exact_title_seen else 0.68
    row = {
        "id": title_id,
        "source": "mangadex",
        "sourceFamily": "external_metadata",
        "field": "metadata.tag",
        "normalizedTags": tags,
        "identityScore": identity_score,
        "identitySignals": {
            "queryTitle": title,
            "exactTitleSeen": 1.0 if exact_title_seen else 0.0,
            "rank": 1,
        },
        "sourceReliability": 0.78,
        "fieldReliability": 0.75,
        "url": f"https://mangadex.org/title/{best.get('id', '')}",
        "rawTags": raw_tags,
        "resultTitles": values[:12],
    }
    if cache is not None:
        cached_row = dict(row)
        cached_row.pop("id", None)
        with cache_lock or Lock():
            cache[key] = {"row": cached_row}
    return row


def main() -> int:
    parser = argparse.ArgumentParser(description="Collect MangaDex genre evidence by title search.")
    parser.add_argument("--db", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--timeout", type=float, default=12.0)
    parser.add_argument("--delay", type=float, default=0.0)
    parser.add_argument("--cache", default="")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--only-forced", action="store_true")
    parser.add_argument("--only-missing-description", action="store_true")
    parser.add_argument("--flush-every", type=int, default=100)
    args = parser.parse_args()

    output = Path(args.output)
    existing_rows, seen_ids = load_existing_output(output) if args.resume else ([], set())
    rows = [
        (key, item)
        for key, item in iter_titles(load_db(Path(args.db)))
        if key not in seen_ids and needs_external(item, args.only_forced, args.only_missing_description)
    ]
    if args.limit:
        rows = rows[: args.limit]
    cache_path = Path(args.cache) if args.cache else None
    cache = load_cache(cache_path)
    cache_lock = Lock()
    results: list[dict[str, Any]] = list(existing_rows)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in results) + ("\n" if results else ""), encoding="utf-8")
    processed = 0
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = [executor.submit(fetch_one, key, item, args.timeout, args.delay, cache, cache_lock) for key, item in rows]
        for index, future in enumerate(as_completed(futures), 1):
            row = future.result()
            processed += 1
            if row:
                results.append(row)
            if index % args.flush_every == 0 or index == len(futures):
                results.sort(key=lambda item: int(item["id"]) if str(item["id"]).isdigit() else 0)
                output.write_text(
                    "\n".join(json.dumps(item, ensure_ascii=False) for item in results) + ("\n" if results else ""),
                    encoding="utf-8",
                )
                save_cache(cache_path, cache)
                print(f"processed {index}/{len(futures)} matched {len(results)} cache {len(cache)}")
    results.sort(key=lambda row: int(row["id"]) if str(row["id"]).isdigit() else 0)
    output.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in results) + ("\n" if results else ""), encoding="utf-8")
    save_cache(cache_path, cache)
    print(f"wrote {len(results)} evidence rows to {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
