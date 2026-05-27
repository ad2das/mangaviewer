#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import subprocess
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any


SEARCH_ENDPOINT = "https://gateway-kw.kakao.com/search/v2/content"

GENRE_MAP = {
    "드라마": "드라마",
    "판타지": "판타지",
    "액션": "액션",
    "무협": "무협",
    "로맨스": "로맨스",
    "순정": "순정",
    "학원": "학원",
    "개그": "개그",
    "코믹": "개그",
    "일상": "일상",
    "스릴러": "스릴러",
    "공포": "공포",
    "미스터리": "미스터리",
    "추리": "추리",
    "스포츠": "스포츠",
    "음악": "음악",
    "요리": "요리",
    "BL": "BL",
    "백합": "백합",
    "성인": "성인",
}


def write_text_atomic(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    target = path.resolve()
    tmp = target.with_name(target.name + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(target)


def load_db(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def normalize_title(value: str) -> str:
    text = re.sub(r"\([^)]*\)", "", value or "")
    text = re.sub(r"[\W_]+", "", text, flags=re.UNICODE)
    return text.casefold()


def similarity(left: str, right: str) -> float:
    left_key = normalize_title(left)
    right_key = normalize_title(right)
    if not left_key or not right_key:
        return 0.0
    score = SequenceMatcher(None, left_key, right_key).ratio()
    if left_key in right_key or right_key in left_key:
        score = max(score, min(len(left_key), len(right_key)) / max(len(left_key), len(right_key)))
    return score


def fetch_json(url: str, timeout: float) -> dict[str, Any] | None:
    try:
        completed = subprocess.run(
            [
                "curl.exe",
                "-L",
                "--silent",
                "--show-error",
                "--max-time",
                str(max(2, int(timeout))),
                "-A",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36",
                "-H",
                "Referer: https://webtoon.kakao.com/",
                "-H",
                "Accept: application/json, text/plain, */*",
                url,
            ],
            capture_output=True,
            check=False,
            timeout=timeout + 2.0,
        )
    except Exception:
        return None
    if not completed.stdout:
        return None
    try:
        return json.loads(completed.stdout.decode("utf-8", errors="replace"))
    except json.JSONDecodeError:
        return None


def map_genres(value: str) -> list[str]:
    tags: list[str] = []
    for part in re.split(r"[/,·|>\s]+", value or ""):
        tag = GENRE_MAP.get(part.strip())
        if tag and tag not in tags:
            tags.append(tag)
    return tags


def title_queries(title: str) -> list[str]:
    cleaned = re.sub(r"\s*(시즌|외전|완전판|개정판)\s*\d*$", "", title).strip()
    variants = [title, cleaned]
    result: list[str] = []
    for value in variants:
        if value and value not in result:
            result.append(value)
    return result


def search_one(key: str, item: dict[str, Any], timeout: float) -> dict[str, Any] | None:
    title = str(item.get("name", "")).strip()
    if not title:
        return None
    best: tuple[float, dict[str, Any]] | None = None
    for query in title_queries(title):
        url = SEARCH_ENDPOINT + "?" + urllib.parse.urlencode({"limit": "5", "offset": "0", "word": query})
        payload = fetch_json(url, timeout)
        contents = ((payload or {}).get("data") or {}).get("content") or []
        if not isinstance(contents, list):
            continue
        for candidate in contents:
            if not isinstance(candidate, dict):
                continue
            result_title = str(candidate.get("title") or "")
            score = similarity(title, result_title)
            if score < 0.86:
                continue
            tags = map_genres(str(candidate.get("genre") or ""))
            if not tags:
                continue
            if best is None or score > best[0]:
                best = (score, candidate | {"tags": tags, "query": query})
        if best and best[0] >= 0.98:
            break
    if not best:
        return None
    score, candidate = best
    content_id = candidate.get("id")
    return {
        "id": key,
        "source": "kakao",
        "sourceFamily": "official_platform",
        "field": "official.genre",
        "normalizedTags": candidate["tags"],
        "identityScore": round(score, 4),
        "identitySignals": {"title": round(score, 4)},
        "sourceReliability": 1.0,
        "fieldReliability": 1.0,
        "url": f"https://webtoon.kakao.com/content/{candidate.get('seoId')}/{content_id}" if content_id else "https://webtoon.kakao.com/search",
        "query": candidate.get("query", ""),
        "resultTitle": candidate.get("title", ""),
        "officialGenre": candidate.get("genre", ""),
    }


def load_existing(path: Path) -> tuple[list[dict[str, Any]], set[str]]:
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
        rows.append(row)
        if row.get("id"):
            seen.add(str(row["id"]))
    return rows, seen


def is_forced(item: dict[str, Any]) -> bool:
    classification = item.get("classification") if isinstance(item.get("classification"), dict) else {}
    flags = classification.get("flags") if isinstance(classification.get("flags"), dict) else {}
    return bool(flags.get("nonEmptyForced"))


def collect(
    db_path: Path,
    output: Path,
    cache_path: Path,
    limit: int,
    workers: int,
    timeout: float,
    flush_every: int,
    resume: bool,
    only_forced: bool,
    retry_misses: bool,
) -> None:
    data = load_db(db_path)
    titles = data.get("titles", {}) if isinstance(data.get("titles"), dict) else {}
    existing, seen = load_existing(output) if resume else ([], set())
    cache: dict[str, Any] = {}
    if resume and cache_path.exists():
        try:
            loaded = json.loads(cache_path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                cache = loaded
        except json.JSONDecodeError:
            cache = {}
    rows = [
        (str(key), item)
        for key, item in titles.items()
        if isinstance(item, dict)
        and str(key) not in seen
        and (retry_misses or str(key) not in cache)
        and str(item.get("name", "")).strip()
        and (not only_forced or is_forced(item))
    ]
    rows.sort(key=lambda pair: int(pair[0]) if pair[0].isdigit() else 0)
    if limit:
        rows = rows[:limit]
    results = list(existing)
    output.parent.mkdir(parents=True, exist_ok=True)
    write_text_atomic(output, "\n".join(json.dumps(row, ensure_ascii=False) for row in results) + ("\n" if results else ""))
    with ThreadPoolExecutor(max_workers=max(1, workers)) as executor:
        futures = [executor.submit(search_one, key, item, timeout) for key, item in rows]
        future_keys = {future: key for future, (key, _) in zip(futures, rows)}
        for index, future in enumerate(as_completed(futures), 1):
            key = future_keys[future]
            row = future.result()
            if row:
                results.append(row)
                cache[key] = row
            else:
                cache[key] = None
            if index % flush_every == 0 or index == len(futures):
                results.sort(key=lambda item: int(item["id"]) if str(item["id"]).isdigit() else 0)
                write_text_atomic(
                    output,
                    "\n".join(json.dumps(item, ensure_ascii=False) for item in results) + ("\n" if results else ""),
                )
                write_text_atomic(cache_path, json.dumps(dict(cache), ensure_ascii=False, indent=2))
                print(f"processed {index}/{len(futures)} matched {len(results)}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Collect official Kakao Webtoon genre evidence.")
    parser.add_argument("--db", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--cache", default="tools/classification/kakao-webtoon-cache.json")
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--timeout", type=float, default=6.0)
    parser.add_argument("--flush-every", type=int, default=100)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--only-forced", action="store_true")
    parser.add_argument("--retry-misses", action="store_true")
    args = parser.parse_args()
    collect(
        Path(args.db),
        Path(args.output),
        Path(args.cache),
        args.limit,
        args.workers,
        args.timeout,
        args.flush_every,
        args.resume,
        args.only_forced,
        args.retry_misses,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
