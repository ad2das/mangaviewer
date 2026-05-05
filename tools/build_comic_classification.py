#!/usr/bin/env python3
"""
Build comic-classification.json from source comic genre pages.

The Android app reads only the generated JSON. This script is an offline
maintenance tool so comic genre lookup does not happen on user devices.
"""

from __future__ import annotations

import argparse
import html as html_lib
import json
import re
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple
from urllib.parse import quote, urljoin

from build_webtoon_classification import (
    DEFAULT_SOURCE_ROOT,
    SourceParser,
    SourceTitle,
    http_get,
    merge_tags,
    normalize_source_root,
    resolve_source_root,
)


COMIC_GENRES = [
    "17",
    "드라마",
    "액션",
    "SF",
    "TS",
    "개그",
    "게임",
    "공포",
    "도박",
    "호러",
    "라노벨",
    "러브코미디",
    "로맨스",
    "먹방",
    "미스터리",
    "백합",
    "붕탁",
    "성인",
    "순정",
    "스릴러",
    "스포츠",
    "시대",
    "애니화",
    "판타지",
    "학원",
    "BL",
    "여장",
    "역사",
    "요리",
    "음악",
    "이세계",
    "일상",
    "전생",
    "추리",
]
COMIC_GENRE_SET = set(COMIC_GENRES)

COMIC_DAYS = ["recent", "10", "11", "12", "14", "16", "15", "13", "20"]
COMIC_ALPHABETS = ["ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "a", "0"]


def comic_genre_path(genre: str, order: str) -> str:
    return f"/cm?type1=genre&type2={quote(genre, encoding='euc-kr')}&o={order}"


def comic_day_path(value: str, order: str) -> str:
    return f"/cm?type1=complete&type2={value}&o={order}"


def comic_alphabet_path(value: str, order: str) -> str:
    return f"/cm?type1=alphabet&type2={quote(value, encoding='euc-kr')}&o={order}"


def source_paths() -> List[Tuple[str, str]]:
    paths: List[Tuple[str, str]] = [("/cm?type1=complete&type2=recent&o=f", "")]
    for value in COMIC_DAYS:
        paths.append((comic_day_path(value, "n"), ""))
    for genre in COMIC_GENRES:
        paths.append((comic_genre_path(genre, "n"), normalize_comic_genre(genre)))
    for alphabet in COMIC_ALPHABETS:
        paths.append((comic_alphabet_path(alphabet, "n"), ""))
    return list(dict.fromkeys(paths))


def normalize_comic_genre(genre: str) -> str:
    if genre == "17":
        return "성인"
    if genre == "호러":
        return "공포"
    return genre


def normalize_comic_genres(value: str) -> List[str]:
    value = re.sub(r"\s+", " ", value or "").strip()
    if not value:
        return []
    lower = value.lower()
    checks = [
        ("성인", ["17", "19", "19금", "성인", "어른", "고수위"]),
        ("BL", ["bl", "비엘", "보이즈러브"]),
        ("SF", ["sf", "에스에프", "우주", "로봇", "미래", "사이버"]),
        ("TS", ["ts", "성전환", "여체화", "남체화"]),
        ("액션", ["액션", "격투", "배틀", "전투"]),
        ("개그", ["개그", "코믹", "코미디"]),
        ("게임", ["게임"]),
        ("공포", ["공포", "호러", "괴담"]),
        ("도박", ["도박", "카지노", "마작"]),
        ("라노벨", ["라노벨", "라이트노벨"]),
        ("러브코미디", ["러브코미디", "러브 코미디", "럽코"]),
        ("로맨스", ["로맨스", "연애"]),
        ("먹방", ["먹방"]),
        ("미스터리", ["미스터리"]),
        ("백합", ["백합", "gl"]),
        ("붕탁", ["붕탁"]),
        ("순정", ["순정"]),
        ("스릴러", ["스릴러", "범죄"]),
        ("스포츠", ["스포츠"]),
        ("시대", ["시대", "사극"]),
        ("애니화", ["애니화", "애니메이션"]),
        ("판타지", ["판타지", "마법", "마왕", "용사", "던전"]),
        ("학원", ["학원", "학교"]),
        ("여장", ["여장", "남장"]),
        ("역사", ["역사"]),
        ("요리", ["요리"]),
        ("음악", ["음악"]),
        ("이세계", ["이세계"]),
        ("일상", ["일상"]),
        ("전생", ["전생", "환생"]),
        ("추리", ["추리"]),
        ("드라마", ["드라마", "성장"]),
    ]
    result: List[str] = []
    direct = normalize_comic_genre(value)
    if direct in COMIC_GENRE_SET:
        result.append(direct)
    for tag, needles in checks:
        if any(needle.lower() in lower for needle in needles) and tag not in result:
            result.append(tag)
    return result


def meaningful_tags(tags: Iterable[str]) -> List[str]:
    return [tag for tag in merge_tags(tags) if tag and tag != "미분류"]


def infer_comic_tags(title: SourceTitle) -> List[str]:
    text = " ".join([title.name or "", title.release or "", " ".join(title.source_tags or [])]).lower()
    result: List[str] = []

    def add(tag: str, *needles: str) -> None:
        if any(needle.lower() in text for needle in needles) and tag not in result:
            result.append(tag)

    add("BL", "bl", "비엘", "보이즈러브")
    add("SF", "sf", "우주", "로봇", "미래", "사이버")
    add("TS", "ts", "성전환", "여체화", "남체화")
    add("액션", "액션", "격투", "전투", "전쟁", "검", "킬러", "암살")
    add("개그", "개그", "코미디", "러브코미디", "럽코")
    add("게임", "게임", "플레이어", "게이머")
    add("공포", "공포", "호러", "괴담", "귀신", "좀비")
    add("도박", "도박", "카지노", "마작", "포커")
    add("라노벨", "라노벨", "라이트노벨")
    add("러브코미디", "러브코미디", "러브 코미디", "럽코")
    add("로맨스", "로맨스", "연애", "첫사랑", "사랑", "고백", "결혼")
    add("요리", "먹방", "요리", "식당", "셰프", "요리사")
    add("미스터리", "미스터리", "추리", "탐정", "사건")
    add("백합", "백합", "gl")
    add("순정", "순정")
    add("스릴러", "스릴러", "범죄", "살인", "납치", "추적")
    add("스포츠", "스포츠", "축구", "야구", "농구", "배구", "복싱")
    add("시대", "시대", "사극", "전국", "에도", "왕국", "제국")
    add("학원", "학교", "학원", "학생", "고교", "고등학교", "동아리")
    add("여장", "여장", "남장")
    add("음악", "음악", "밴드", "아이돌", "가수")
    add("이세계", "이세계", "전생", "환생", "용사", "마왕", "던전", "마법")
    add("전생", "전생", "환생")
    add("판타지", "판타지", "이세계", "전생", "환생", "용사", "마왕", "던전", "마법")
    add("일상", "일상", "힐링", "가족", "직장", "회사")
    add("드라마", "드라마", "휴먼", "성장")
    return result


def fetch_source_titles(root: str, delay: float, max_paths: int, workers: int) -> Dict[int, SourceTitle]:
    titles: Dict[int, SourceTitle] = {}
    paths = source_paths()
    if max_paths > 0:
        paths = paths[:max_paths]
    with ThreadPoolExecutor(max_workers=max(1, workers)) as executor:
        futures = {executor.submit(fetch_source_path_titles, root, path, delay): (path, genre) for path, genre in paths}
        for future in as_completed(futures):
            path, genre = futures[future]
            try:
                parsed = future.result()
            except Exception as exc:
                print(f"warn: source fetch failed: {path}: {exc}", file=sys.stderr)
                continue
            for toon_id, title in parsed.items():
                existing = titles.get(toon_id)
                if existing is None:
                    existing = title
                    titles[toon_id] = existing
                if genre and genre not in existing.source_tags:
                    existing.source_tags.append(genre)
    return titles


def enrich_source_details(root: str, titles: Dict[int, SourceTitle], delay: float, workers: int, cache_path: Optional[Path]) -> None:
    detail_cache = load_detail_cache(cache_path)
    targets: List[SourceTitle] = []
    cache_hits = 0
    for title in titles.values():
        cached = detail_cache.get(str(title.toon_id))
        if apply_cached_detail(title, cached):
            cache_hits += 1
            continue
        targets.append(title)
    print(f"detail cache hits: {cache_hits} fetches: {len(targets)}", file=sys.stderr)
    if not targets:
        return
    completed = 0
    with ThreadPoolExecutor(max_workers=max(1, workers)) as executor:
        futures = {executor.submit(fetch_source_detail_tags, root, title.toon_id, delay): title for title in targets}
        for future in as_completed(futures):
            title = futures[future]
            try:
                tags, description = future.result()
            except Exception:
                tags, description = [], ""
            if tags:
                title.source_tags = merge_tags(tags, meaningful_tags(title.source_tags))
            detail_cache[str(title.toon_id)] = {"name": title.name, "tags": tags, "description": description}
            completed += 1
            if completed % 500 == 0 or completed == len(targets):
                print(f"detail progress: {completed}/{len(targets)}", file=sys.stderr)
                save_detail_cache(cache_path, detail_cache)
    save_detail_cache(cache_path, detail_cache)


def fetch_source_detail_tags(root: str, toon_id: int, delay: float) -> Tuple[List[str], str]:
    html = http_get(urljoin(root.rstrip("/") + "/", f"cl?toon={toon_id}"), delay, timeout=12)
    return parse_source_detail(html)


def parse_source_detail(html: str) -> Tuple[List[str], str]:
    tags: List[str] = []
    match = re.search(r"<strong>\s*장르\s*:\s*</strong>\s*([^<]+)", html, flags=re.IGNORECASE)
    if match:
        for raw in re.split(r"[/,·ㆍ|]+", html_lib.unescape(match.group(1))):
            for tag in normalize_comic_genres(raw):
                if tag not in tags:
                    tags.append(tag)
    desc = ""
    desc_match = re.search(r'<meta\s+name=["\']description["\']\s+content=["\']([^"\']*)', html, flags=re.IGNORECASE)
    if desc_match:
        desc = re.sub(r"\s+", " ", html_lib.unescape(desc_match.group(1))).strip()
    return tags, desc


def load_detail_cache(path: Optional[Path]) -> Dict[str, object]:
    if path is None or not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        entries = data.get("entries", data)
        return entries if isinstance(entries, dict) else {}
    except Exception:
        return {}


def save_detail_cache(path: Optional[Path], cache: Dict[str, object]) -> None:
    if path is None:
        return
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"version": 1, "entries": cache}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except Exception:
        pass


def apply_cached_detail(title: SourceTitle, cached: object) -> bool:
    if not isinstance(cached, dict):
        return False
    tags = cached.get("tags")
    if not isinstance(tags, list):
        return False
    clean_tags = meaningful_tags(str(tag) for tag in tags)
    if clean_tags:
        title.source_tags = merge_tags(clean_tags, meaningful_tags(title.source_tags))
    return bool(clean_tags)


def fetch_source_path_titles(root: str, path: str, delay: float) -> Dict[int, SourceTitle]:
    html = http_get(urljoin(root.rstrip("/") + "/", path.lstrip("/")), delay)
    parser = SourceParser(root)
    parser.feed(html)
    return parser.titles


def merge_existing(path: Path) -> Dict[str, object]:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def build_output(existing: Dict[str, object], source_titles: Dict[int, SourceTitle], source_root: str) -> Dict[str, object]:
    existing_titles = existing.get("titles", {}) if isinstance(existing.get("titles"), dict) else {}
    titles: Dict[str, object] = {}
    for key, item in existing_titles.items():
        if isinstance(item, dict) and item.get("manual", False):
            titles[key] = item
    for toon_id, source in source_titles.items():
        previous = existing_titles.get(str(toon_id), {})
        if not isinstance(previous, dict):
            previous = {}
        if previous.get("manual", False):
            titles[str(toon_id)] = previous
            continue
        manual_tags = previous.get("manualTags") if isinstance(previous.get("manualTags"), list) else []
        source_tags = merge_tags(meaningful_tags(source.source_tags), meaningful_tags(previous.get("sourceTags") if isinstance(previous.get("sourceTags"), list) else []))
        inferred_tags = [] if manual_tags or source_tags else infer_comic_tags(source)
        if not manual_tags and not source_tags and not inferred_tags:
            source_tags = ["미분류"]
        next_item = dict(previous)
        next_item.update(
            {
                "name": source.name,
                "thumb": source.thumb or previous.get("thumb", ""),
                "release": source.release or previous.get("release", ""),
            }
        )
        if manual_tags:
            next_item["manualTags"] = manual_tags
        if source_tags:
            next_item["sourceTags"] = source_tags
        else:
            next_item.pop("sourceTags", None)
        if inferred_tags:
            next_item["inferredTags"] = inferred_tags
        elif "inferredTags" in next_item:
            next_item.pop("inferredTags", None)
        titles[str(toon_id)] = next_item
    return {
        "version": int(existing.get("version", 1)) if existing else 1,
        "updated": date.today().isoformat(),
        "sourceRoot": source_root,
        "sources": ["source-comic-genre"],
        "titles": dict(sorted(titles.items(), key=lambda item: int(item[0]))),
    }


def write_unclassified(path: Path, data: Dict[str, object]) -> None:
    titles = data.get("titles", {}) if isinstance(data.get("titles"), dict) else {}
    unclassified = []
    for key, item in titles.items():
        if not isinstance(item, dict):
            continue
        tags = merge_tags(item.get("manualTags") if isinstance(item.get("manualTags"), list) else [], item.get("sourceTags") if isinstance(item.get("sourceTags"), list) else [], item.get("inferredTags") if isinstance(item.get("inferredTags"), list) else [], item.get("tags") if isinstance(item.get("tags"), list) else [])
        if not meaningful_tags(tags):
            unclassified.append({"id": int(key), "name": item.get("name", ""), "thumb": item.get("thumb", "")})
    path.write_text(json.dumps(unclassified, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def copy_outputs(output: Path, asset_output: Path) -> None:
    asset_output.parent.mkdir(parents=True, exist_ok=True)
    asset_output.write_text(output.read_text(encoding="utf-8"), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build comic-classification.json from source comic genres.")
    parser.add_argument("--output", default="comic-classification.json")
    parser.add_argument("--asset-output", default="app/src/main/assets/comic-classification.json")
    parser.add_argument("--source-root", default=DEFAULT_SOURCE_ROOT)
    parser.add_argument("--no-source-root-resolve", action="store_true")
    parser.add_argument("--max-source-paths", type=int, default=0, help="0 means all configured source paths")
    parser.add_argument("--delay", type=float, default=0.35)
    parser.add_argument("--source-workers", type=int, default=16)
    parser.add_argument("--no-source-detail-fetch", action="store_true")
    parser.add_argument("--source-detail-workers", type=int, default=16)
    parser.add_argument("--source-detail-cache", default=".comic-source-detail-cache.json")
    parser.add_argument("--no-source-detail-cache", action="store_true")
    parser.add_argument("--unclassified-output", default="")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    output = Path(args.output)
    existing = merge_existing(output)
    source_root = resolve_source_root(args.source_root, auto_resolve=not args.no_source_root_resolve)
    if source_root != normalize_source_root(args.source_root):
        print(f"resolved source root: {args.source_root} -> {source_root}", file=sys.stderr)

    print("fetching comic source titles...", file=sys.stderr)
    source_titles = fetch_source_titles(source_root, args.delay, args.max_source_paths, args.source_workers)
    print(f"source titles: {len(source_titles)}", file=sys.stderr)
    if not args.no_source_detail_fetch:
        print(f"fetching comic detail genres: {len(source_titles)}", file=sys.stderr)
        enrich_source_details(
            source_root,
            source_titles,
            args.delay,
            args.source_detail_workers,
            None if args.no_source_detail_cache else Path(args.source_detail_cache),
        )

    data = build_output(existing, source_titles, source_root)
    text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    if args.dry_run:
        print(text)
    else:
        output.write_text(text, encoding="utf-8")
        if args.asset_output:
            copy_outputs(output, Path(args.asset_output))
        if args.unclassified_output:
            write_unclassified(Path(args.unclassified_output), data)
        print(f"wrote {output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
