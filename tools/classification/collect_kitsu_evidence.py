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


CATEGORY_MAP = {
    "action": ["액션"],
    "adventure": ["판타지"],
    "comedy": ["개그"],
    "drama": ["드라마"],
    "ecchi": ["성인"],
    "fantasy": ["판타지"],
    "gender bender": ["TS"],
    "harem": ["성인", "로맨스"],
    "horror": ["공포"],
    "isekai": ["이세계", "판타지"],
    "magic": ["판타지"],
    "martial arts": ["액션", "무협"],
    "mystery": ["미스터리"],
    "psychological": ["스릴러"],
    "romance": ["로맨스"],
    "school": ["학원"],
    "school life": ["학원"],
    "sci-fi": ["SF"],
    "science fiction": ["SF"],
    "seinen": ["드라마"],
    "shoujo": ["순정"],
    "shounen": ["액션"],
    "slice of life": ["일상"],
    "sports": ["스포츠"],
    "supernatural": ["판타지"],
    "thriller": ["스릴러"],
    "vampire": ["공포", "판타지"],
    "yaoi": ["BL"],
    "yuri": ["백합"],
}


def normalize_title(value: str) -> str:
    text = re.sub(r"\([^)]*\)", "", value or "")
    return re.sub(r"[\W_]+", "", text, flags=re.UNICODE).casefold()


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
    if not path.exists():
        return [], set()
    rows: list[dict[str, Any]] = []
    seen: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(row, dict):
            rows.append(row)
            if row.get("id"):
                seen.add(str(row["id"]))
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


def request_json(url: str, timeout: float) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "mangaviewer-genre-audit/1.0",
            "Accept": "application/vnd.api+json",
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8", errors="replace"))


def title_values(attributes: dict[str, Any]) -> list[str]:
    values: list[str] = []
    for key in ("canonicalTitle", "abbreviatedTitles"):
        value = attributes.get(key)
        if isinstance(value, str):
            values.append(value)
        elif isinstance(value, list):
            values.extend(str(item) for item in value if item)
    titles = attributes.get("titles")
    if isinstance(titles, dict):
        values.extend(str(value) for value in titles.values() if value)
    return values


def category_tags(data: dict[str, Any]) -> list[str]:
    mapped: list[str] = []
    for item in data.get("included", []) if isinstance(data.get("included"), list) else []:
        if not isinstance(item, dict) or item.get("type") != "categories":
            continue
        attrs = item.get("attributes") if isinstance(item.get("attributes"), dict) else {}
        title = str(attrs.get("title", "")).strip().casefold()
        slug = str(attrs.get("slug", "")).replace("-", " ").strip().casefold()
        mapped.extend(CATEGORY_MAP.get(title, []))
        mapped.extend(CATEGORY_MAP.get(slug, []))
    return unique(mapped)


def fetch_one(
    title_id: str,
    item: dict[str, Any],
    timeout: float,
    delay: float,
    cache: dict[str, Any],
    cache_lock: Lock,
) -> dict[str, Any] | None:
    title = str(item.get("name", "")).strip()
    key = normalize_title(title)
    if len(key) < 4:
        return None
    cached = cache.get(key)
    if isinstance(cached, dict):
        if cached.get("miss"):
            return None
        row = cached.get("row")
        if isinstance(row, dict):
            next_row = dict(row)
            next_row["id"] = title_id
            return next_row
    if delay:
        time.sleep(delay)
    url = "https://kitsu.io/api/edge/manga?" + urllib.parse.urlencode(
        {
            "filter[text]": title,
            "page[limit]": "3",
            "include": "categories",
        }
    )
    data: dict[str, Any] | None = None
    for attempt in range(2):
        try:
            data = request_json(url, timeout)
            break
        except Exception:
            if attempt == 0:
                time.sleep(0.5)
    if data is None:
        return None
    results = data.get("data") if isinstance(data.get("data"), list) else []
    if not results:
        with cache_lock:
            cache[key] = {"miss": True}
        return None
    first = results[0]
    attrs = first.get("attributes") if isinstance(first, dict) else {}
    if not isinstance(attrs, dict):
        return None
    values = title_values(attrs)
    normalized_values = [normalize_title(value) for value in values]
    exact = key in normalized_values
    title_contains = any(key and (key in value or value in key) and min(len(key), len(value)) >= 5 for value in normalized_values)
    if not exact and not title_contains:
        with cache_lock:
            cache[key] = {"miss": True, "reason": "identity_rejected", "resultTitles": values[:8]}
        return None
    tags = category_tags(data)
    if not tags:
        with cache_lock:
            cache[key] = {"miss": True, "reason": "no_categories", "resultTitles": values[:8]}
        return None
    row = {
        "id": title_id,
        "source": "kitsu",
        "sourceFamily": "external_metadata",
        "field": "metadata.tag",
        "normalizedTags": tags,
        "identityScore": 0.88 if exact else 0.72,
        "identitySignals": {
            "queryTitle": title,
            "exactTitleSeen": 1.0 if exact else 0.0,
            "titleContains": 1.0 if title_contains else 0.0,
        },
        "sourceReliability": 0.72,
        "fieldReliability": 0.75,
        "url": f"https://kitsu.io/manga/{first.get('id', '')}",
        "resultTitles": values[:12],
    }
    cached_row = dict(row)
    cached_row.pop("id", None)
    with cache_lock:
        cache[key] = {"row": cached_row}
    return row


def main() -> int:
    parser = argparse.ArgumentParser(description="Collect Kitsu genre evidence by title search.")
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
    results = list(existing_rows)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in results) + ("\n" if results else ""), encoding="utf-8")
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = [executor.submit(fetch_one, key, item, args.timeout, args.delay, cache, cache_lock) for key, item in rows]
        for index, future in enumerate(as_completed(futures), 1):
            row = future.result()
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
    save_cache(cache_path, cache)
    print(f"wrote {len(results)} evidence rows to {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
