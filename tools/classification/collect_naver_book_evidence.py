#!/usr/bin/env python3
from __future__ import annotations

import argparse
import html
import json
import re
import subprocess
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from threading import Lock
from typing import Any

from collect_external_evidence import infer_tags_from_text


NAVER_BOOK_URL = "https://search.shopping.naver.com/book/search"


def normalize_title(value: str) -> str:
    text = html.unescape(value or "")
    text = re.sub(r"\([^)]*\)", "", text)
    text = re.sub(r"\[[^]]*\]", "", text)
    text = re.sub(r"\s*(?:완전판|신장판|개정판|컬러판|소장판|애장판|특장판|한정판|외전).*$", "", text)
    text = re.sub(r"\s*(?:제)?\d+\s*(?:권|화|부)?\s*$", "", text)
    text = re.sub(r"\s+[상중하]$", "", text)
    return re.sub(r"[\W_]+", "", text, flags=re.UNICODE).casefold()


def query_title(value: str) -> str:
    text = html.unescape(value or "").strip()
    text = re.sub(r"\([^)]*\)", "", text)
    text = re.sub(r"\[[^]]*\]", "", text)
    text = re.sub(r"\s*(?:완전판|신장판|개정판|컬러판|소장판|애장판|특장판|한정판|외전).*$", "", text)
    text = re.sub(r"\s*(?:제)?\d+\s*(?:권|화|부)?\s*$", "", text)
    return re.sub(r"\s+", " ", text).strip()


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
        if not isinstance(row, dict):
            continue
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


def request_text(url: str, timeout: float) -> str:
    try:
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
        if completed.stdout:
            return completed.stdout[:700_000].decode("utf-8", errors="replace")
    except Exception:
        pass
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36",
            "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.5,en;q=0.3",
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read(700_000).decode("utf-8", errors="replace")


def extract_next_data(page: str) -> dict[str, Any]:
    match = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', page, flags=re.DOTALL)
    if not match:
        return {}
    try:
        return json.loads(html.unescape(match.group(1)))
    except Exception:
        return {}


def walk(value: Any):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def book_results(page_data: dict[str, Any]) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    seen: set[str] = set()
    for obj in walk(page_data):
        if not isinstance(obj, dict):
            continue
        title = obj.get("title")
        description = obj.get("description")
        if not isinstance(title, str) or not isinstance(description, str):
            continue
        if "id" not in obj or "publisher" not in obj:
            continue
        key = str(obj.get("id"))
        if key in seen:
            continue
        seen.add(key)
        results.append(obj)
    return results


def map_book_tags(result: dict[str, Any]) -> list[str]:
    text = " ".join(
        str(result.get(field, ""))
        for field in ("title", "subtitle", "description")
        if result.get(field)
    )
    tags = infer_tags_from_text(text)
    if result.get("isAdult") in {1, "1", True}:
        tags.append("성인")
    return unique(tags)


def choose_result(query_key: str, results: list[dict[str, Any]]) -> tuple[dict[str, Any] | None, float, dict[str, float]]:
    best: dict[str, Any] | None = None
    best_score = 0.0
    best_signals: dict[str, float] = {}
    for index, result in enumerate(results):
        title = str(result.get("title", ""))
        normalized = normalize_title(title)
        exact = query_key == normalized
        contains = bool(query_key and normalized and (query_key in normalized or normalized in query_key) and min(len(query_key), len(normalized)) >= 4)
        if not exact and not contains:
            continue
        description = str(result.get("description", "")).strip()
        if not description and result.get("isAdult") not in {1, "1", True}:
            continue
        score = 0.90 if exact else 0.76
        score -= min(index, 4) * 0.03
        if score > best_score:
            best = result
            best_score = score
            best_signals = {"exactTitleSeen": 1.0 if exact else 0.0, "titleContains": 1.0 if contains else 0.0, "rank": float(index + 1)}
    return best, best_score, best_signals


def fetch_one(
    title_id: str,
    item: dict[str, Any],
    timeout: float,
    delay: float,
    cache: dict[str, Any],
    cache_lock: Lock,
) -> dict[str, Any] | None:
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
    url = NAVER_BOOK_URL + "?" + urllib.parse.urlencode({"bookTabType": "ALL", "query": query})
    page: str | None = None
    for attempt in range(2):
        try:
            page = request_text(url, timeout)
            break
        except Exception:
            if attempt == 0:
                time.sleep(0.5)
    if not page:
        return None
    results = book_results(extract_next_data(page))
    best, identity_score, signals = choose_result(key, results)
    if not best:
        with cache_lock:
            cache[key] = {"miss": True}
        return None
    tags = map_book_tags(best)
    if not tags:
        with cache_lock:
            cache[key] = {"miss": True, "reason": "no_genre_terms", "resultTitle": best.get("title", "")}
        return None
    row = {
        "id": title_id,
        "source": "naver_book",
        "sourceFamily": "external_metadata",
        "field": "metadata.description",
        "normalizedTags": tags,
        "identityScore": round(identity_score, 4),
        "identitySignals": {
            "queryTitle": query,
            **signals,
        },
        "sourceReliability": 0.80,
        "fieldReliability": 0.70,
        "url": f"https://search.shopping.naver.com/book/catalog/{best.get('id', '')}",
        "resultTitle": best.get("title", ""),
        "author": best.get("author", ""),
        "publisher": best.get("publisher", ""),
        "description": best.get("description", ""),
        "isAdult": best.get("isAdult", 0),
    }
    cached_row = dict(row)
    cached_row.pop("id", None)
    with cache_lock:
        cache[key] = {"row": cached_row}
    return row


def main() -> int:
    parser = argparse.ArgumentParser(description="Collect Naver Book description-derived genre evidence.")
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
                with cache_lock:
                    save_cache(cache_path, dict(cache))
                print(f"processed {index}/{len(futures)} matched {len(results)} cache {len(cache)}")
    with cache_lock:
        save_cache(cache_path, dict(cache))
    print(f"wrote {len(results)} evidence rows to {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
