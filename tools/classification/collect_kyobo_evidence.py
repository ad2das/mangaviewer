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
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any


KYBO_SEARCH = "https://search.kyobobook.co.kr/search"


TAG_RULES: list[tuple[tuple[str, ...], list[str]]] = [
    (("판타지", "마왕", "마족", "마법", "용사", "엘프", "드래곤", "이세계", "전생", "던전"), ["판타지"]),
    (("액션", "배틀", "전투", "격투", "히어로", "헌터", "싸움", "전쟁", "모험"), ["액션"]),
    (("무협", "무림", "강호", "검객", "천마"), ["무협", "액션"]),
    (("명랑코믹", "코믹", "개그", "코미디", "유머", "병맛"), ["개그"]),
    (("로맨스", "순정", "첫사랑", "연애", "러브", "신부", "결혼"), ["로맨스"]),
    (("러브코미디", "러브 코미디", "럽코"), ["러브코미디", "로맨스", "개그"]),
    (("BL", "비엘", "보이즈러브", "오메가버스"), ["BL"]),
    (("백합", "GL"), ["백합"]),
    (("스릴러", "서스펜스", "미스터리", "추리", "살인", "사건", "범죄", "복수"), ["스릴러", "미스터리"]),
    (("공포", "호러", "괴담", "좀비", "귀신", "유령", "오컬트"), ["공포"]),
    (("SF", "우주", "로봇", "안드로이드", "미래", "초능력", "바이러스"), ["SF"]),
    (("스포츠", "야구", "축구", "농구", "배구", "테니스", "복싱", "자전거"), ["스포츠"]),
    (("요리", "음식", "먹방", "셰프", "맛집", "레시피"), ["요리"]),
    (("음악", "아이돌", "밴드", "가수", "오디션", "연예계"), ["음악"]),
    (("학원", "학교", "고교", "고등학생", "동급생", "동아리", "청춘"), ["학원"]),
    (("일상", "힐링", "가족", "동물", "육아", "직장"), ["일상"]),
    (("성인", "19금", "어른", "관능", "에로"), ["성인"]),
    (("역사", "시대", "사극", "궁중", "전국시대"), ["역사", "시대"]),
    (("게임", "RPG", "플레이어", "퀘스트", "레벨업"), ["게임", "판타지"]),
]


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


def compact_text(value: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(value or "")).strip()


def strip_tags(value: str) -> str:
    return compact_text(re.sub(r"<[^>]+>", " ", value or ""))


def infer_tags(text: str) -> list[str]:
    found: list[str] = []
    lower = text.casefold()
    for needles, tags in TAG_RULES:
        if any(needle.casefold() in lower for needle in needles):
            for tag in tags:
                if tag not in found:
                    found.append(tag)
    return found


def fetch(url: str, timeout: float) -> str:
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
                "Accept-Language: ko-KR,ko;q=0.9,en-US;q=0.5,en;q=0.3",
                url,
            ],
            capture_output=True,
            check=False,
            timeout=timeout + 2.0,
        )
    except Exception:
        return ""
    return completed.stdout.decode("utf-8", errors="replace") if completed.stdout else ""


def title_queries(title: str) -> list[str]:
    cleaned = re.sub(r"[!！?？~～]+$", "", title).strip()
    compact = re.sub(r"\s+", " ", cleaned)
    variants = [title, cleaned, compact]
    result: list[str] = []
    for value in variants:
        if value and value not in result:
            result.append(value)
    return result


def parse_blocks(page: str) -> list[dict[str, Any]]:
    blocks = re.split(r'<div class="prod_info_box">', page)
    results: list[dict[str, Any]] = []
    for block in blocks[1:8]:
        title_match = re.search(r'<span id="cmdtName_[^"]*">(.*?)</span>', block, flags=re.DOTALL)
        link_match = re.search(r'<a href="([^"]+)" class="prod_info"', block)
        if not title_match:
            continue
        title = strip_tags(title_match.group(1))
        hashtags = [strip_tags(match) for match in re.findall(r'<span class="text">#(.*?)</span>', block, flags=re.DOTALL)]
        info = strip_tags(block[:9000])
        results.append(
            {
                "title": title,
                "url": html.unescape(link_match.group(1)) if link_match else "",
                "hashtags": [tag for tag in hashtags if tag],
                "text": info,
            }
        )
    return results


def search_one(key: str, item: dict[str, Any], timeout: float) -> dict[str, Any] | None:
    title = str(item.get("name", "")).strip()
    if not title:
        return None
    best: tuple[float, dict[str, Any]] | None = None
    for query in title_queries(title):
        url = KYBO_SEARCH + "?" + urllib.parse.urlencode({"keyword": query})
        page = fetch(url, timeout)
        for candidate in parse_blocks(page):
            score = similarity(title, candidate["title"])
            if score < 0.78:
                continue
            evidence_text = " ".join([candidate["title"], *candidate["hashtags"], candidate["text"][:1200]])
            tags = infer_tags(evidence_text)
            if not tags:
                continue
            if best is None or score > best[0]:
                best = (score, candidate | {"tags": tags, "query": query})
        if best and best[0] >= 0.94:
            break
    if not best:
        return None
    score, candidate = best
    return {
        "id": key,
        "source": "kyobo",
        "sourceFamily": "external_metadata",
        "field": "metadata.tag",
        "normalizedTags": candidate["tags"],
        "identityScore": round(score, 4),
        "identitySignals": {"title": round(score, 4)},
        "sourceReliability": 0.78,
        "fieldReliability": 0.76,
        "url": candidate.get("url", ""),
        "query": candidate.get("query", ""),
        "resultTitle": candidate.get("title", ""),
        "hashtags": candidate.get("hashtags", []),
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
    output.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in results) + ("\n" if results else ""), encoding="utf-8")
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
                output.write_text(
                    "\n".join(json.dumps(item, ensure_ascii=False) for item in results) + ("\n" if results else ""),
                    encoding="utf-8",
                )
                cache_path.write_text(json.dumps(cache, ensure_ascii=False, indent=2), encoding="utf-8")
                print(f"processed {index}/{len(futures)} matched {len(results)}")
        time.sleep(0.05)


def main() -> int:
    parser = argparse.ArgumentParser(description="Collect Kyobo search hashtag genre evidence.")
    parser.add_argument("--db", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--cache", default="tools/classification/kyobo-cache.json")
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--timeout", type=float, default=7.0)
    parser.add_argument("--flush-every", type=int, default=50)
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
