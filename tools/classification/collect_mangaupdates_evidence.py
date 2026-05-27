#!/usr/bin/env python3
from __future__ import annotations

import argparse
import html
import json
import re
import subprocess
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from threading import Lock
from typing import Any


GENRE_MAP = {
    "Action": ["액션"],
    "Adult": ["성인"],
    "Adventure": ["판타지"],
    "Comedy": ["개그"],
    "Doujinshi": [],
    "Drama": ["드라마"],
    "Ecchi": ["성인"],
    "Fantasy": ["판타지"],
    "Gender Bender": ["TS"],
    "Harem": ["성인", "로맨스"],
    "Historical": ["시대", "역사"],
    "Horror": ["공포"],
    "Isekai": ["이세계", "판타지"],
    "Josei": ["드라마"],
    "Martial Arts": ["무협", "액션"],
    "Mature": ["성인"],
    "Mecha": ["SF", "액션"],
    "Mystery": ["미스터리"],
    "Psychological": ["스릴러"],
    "Romance": ["로맨스"],
    "School Life": ["학원"],
    "Sci-fi": ["SF"],
    "Seinen": ["드라마"],
    "Shoujo": ["순정"],
    "Shounen": ["액션"],
    "Shounen Ai": ["BL"],
    "Shoujo Ai": ["백합"],
    "Slice of Life": ["일상"],
    "Smut": ["성인"],
    "Sports": ["스포츠"],
    "Supernatural": ["판타지"],
    "Tragedy": ["드라마"],
    "Yaoi": ["BL"],
    "Yuri": ["백합"],
}


def write_text_atomic(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    target = path.resolve()
    tmp = target.with_name(target.name + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(target)


def normalize_title(value: str) -> str:
    text = html.unescape(value or "")
    text = re.sub(r"\([^)]*\)", "", text)
    text = re.sub(r"\[[^]]*\]", "", text)
    text = re.sub(r"\s*(?:완전판|신장판|개정판|컬러판|소장판|애장판|특장판|한정판|외전).*$", "", text)
    text = re.sub(r"\s*(?:제)?\d+\s*(?:권|화|부)?\s*$", "", text)
    return re.sub(r"[\W_]+", "", text, flags=re.UNICODE).casefold()


def query_title(value: str) -> str:
    text = html.unescape(value or "").strip()
    text = re.sub(r"\([^)]*\)", "", text)
    text = re.sub(r"\[[^]]*\]", "", text)
    text = re.sub(r"\s*(?:완전판|신장판|개정판|컬러판|소장판|애장판|특장판|한정판|외전).*$", "", text)
    text = re.sub(r"\s*(?:제)?\d+\s*(?:권|화|부)?\s*$", "", text)
    return re.sub(r"\s+", " ", text).strip()


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


def map_genres(raw: str) -> list[str]:
    mapped: list[str] = []
    for part in [item.strip() for item in raw.split(",")]:
        mapped.extend(GENRE_MAP.get(part, []))
    return unique(mapped)


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


def cached_done(item: dict[str, Any], cache: dict[str, Any], retry_misses: bool) -> bool:
    key = normalize_title(query_title(str(item.get("name", ""))))
    cached = cache.get(key)
    return isinstance(cached, dict) and bool(cached.get("miss")) and not retry_misses


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
    write_text_atomic(path, json.dumps(dict(cache), ensure_ascii=False, indent=2) + "\n")


def request_text(url: str, timeout: float) -> str:
    completed = subprocess.run(
        [
            "curl.exe",
            "-L",
            "--silent",
            "--show-error",
            "--max-time",
            str(int(timeout)),
            "-A",
            "Mozilla/5.0",
            url,
        ],
        check=False,
        capture_output=True,
        timeout=timeout + 2.0,
    )
    return completed.stdout.decode("utf-8", errors="replace") if completed.stdout else ""


def parse_series(page: str) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    pattern = re.compile(
        r'href="(?P<url>https://www\.mangaupdates\.com/series/[^"]+)".{0,500}?className":"fst-italic","children":"(?P<title>[^"]+)".{0,800}?title":"(?P<genres>[^"]+)"',
        flags=re.DOTALL,
    )
    for match in pattern.finditer(page):
        rows.append(
            {
                "url": html.unescape(match.group("url")),
                "title": html.unescape(match.group("title")),
                "genres": html.unescape(match.group("genres")),
            }
        )
    if rows:
        return rows
    fallback = re.compile(
        r'href="(?P<url>https://www\.mangaupdates\.com/series/[^"]+)".{0,300}?<span class="fst-italic">(?P<title>.*?)</span>.{0,500}?title="(?P<genres>[^"]+)"',
        flags=re.DOTALL,
    )
    for match in fallback.finditer(page):
        rows.append(
            {
                "url": html.unescape(match.group("url")),
                "title": re.sub(r"<[^>]+>", "", html.unescape(match.group("title"))),
                "genres": html.unescape(match.group("genres")),
            }
        )
    return rows


def choose_result(query_key: str, rows: list[dict[str, str]]) -> tuple[dict[str, str] | None, float, dict[str, float]]:
    best = None
    best_score = 0.0
    best_signals: dict[str, float] = {}
    for index, row in enumerate(rows):
        normalized = normalize_title(row.get("title", ""))
        exact = query_key == normalized
        contains = bool(query_key and normalized and (query_key in normalized or normalized in query_key) and min(len(query_key), len(normalized)) >= 4)
        if not exact and not contains:
            continue
        tags = map_genres(row.get("genres", ""))
        if not tags:
            continue
        score = 0.92 if exact else 0.76
        score -= min(index, 4) * 0.03
        if score > best_score:
            best = row
            best_score = score
            best_signals = {"exactTitleSeen": 1.0 if exact else 0.0, "titleContains": 1.0 if contains else 0.0, "rank": float(index + 1)}
    return best, best_score, best_signals


def fetch_one(title_id: str, item: dict[str, Any], timeout: float, delay: float, cache: dict[str, Any], cache_lock: Lock) -> dict[str, Any] | None:
    title = str(item.get("name", "")).strip()
    query = query_title(title)
    key = normalize_title(query)
    if len(key) < 3:
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
    url = "https://www.mangaupdates.com/search.html?" + urllib.parse.urlencode({"search": query})
    try:
        page = request_text(url, timeout)
    except Exception:
        return None
    best, identity_score, signals = choose_result(key, parse_series(page))
    if not best:
        with cache_lock:
            cache[key] = {"miss": True}
        return None
    tags = map_genres(best["genres"])
    row = {
        "id": title_id,
        "source": "mangaupdates",
        "sourceFamily": "external_metadata",
        "field": "metadata.genre",
        "normalizedTags": tags,
        "identityScore": round(identity_score, 4),
        "identitySignals": {"queryTitle": query, **signals},
        "sourceReliability": 0.82,
        "fieldReliability": 0.80,
        "url": best["url"],
        "resultTitle": best["title"],
        "rawGenres": best["genres"],
    }
    cached_row = dict(row)
    cached_row.pop("id", None)
    with cache_lock:
        cache[key] = {"row": cached_row}
    return row


def main() -> int:
    parser = argparse.ArgumentParser(description="Collect MangaUpdates genre evidence by title search.")
    parser.add_argument("--db", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--timeout", type=float, default=12.0)
    parser.add_argument("--delay", type=float, default=0.0)
    parser.add_argument("--cache", default="")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--retry-misses", action="store_true")
    parser.add_argument("--only-forced", action="store_true")
    parser.add_argument("--only-missing-description", action="store_true")
    parser.add_argument("--flush-every", type=int, default=100)
    args = parser.parse_args()

    output = Path(args.output)
    existing_rows, seen_ids = load_existing_output(output) if args.resume else ([], set())
    cache_path = Path(args.cache) if args.cache else None
    cache = load_cache(cache_path)
    cache_lock = Lock()
    rows = [
        (key, item)
        for key, item in iter_titles(load_db(Path(args.db)))
        if key not in seen_ids and not cached_done(item, cache, args.retry_misses) and needs_external(item, args.only_forced, args.only_missing_description)
    ]
    if args.limit:
        rows = rows[: args.limit]
    results = list(existing_rows)
    output.parent.mkdir(parents=True, exist_ok=True)
    write_text_atomic(output, "\n".join(json.dumps(row, ensure_ascii=False) for row in results) + ("\n" if results else ""))
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = [executor.submit(fetch_one, key, item, args.timeout, args.delay, cache, cache_lock) for key, item in rows]
        for index, future in enumerate(as_completed(futures), 1):
            row = future.result()
            if row:
                results.append(row)
            if index % args.flush_every == 0 or index == len(futures):
                results.sort(key=lambda item: int(item["id"]) if str(item["id"]).isdigit() else 0)
                write_text_atomic(
                    output,
                    "\n".join(json.dumps(item, ensure_ascii=False) for item in results) + ("\n" if results else ""),
                )
                with cache_lock:
                    save_cache(cache_path, dict(cache))
                print(f"processed {index}/{len(futures)} matched {len(results)} cache {len(cache)}")
    with cache_lock:
        save_cache(cache_path, dict(cache))
    print(f"wrote {len(results)} evidence rows to {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
