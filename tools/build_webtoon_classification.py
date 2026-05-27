#!/usr/bin/env python3
"""
Build webtoon-classification.json from Naver Webtoon genre pages.

The Android app reads only the generated JSON. This script is an offline
maintenance tool so genre lookup does not happen on user devices.
"""

from __future__ import annotations

import argparse
import html as html_lib
import json
import re
import sys
import subprocess
import time
import unicodedata
from dataclasses import dataclass, field
from datetime import date
from difflib import SequenceMatcher
from html.parser import HTMLParser
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.error import URLError
from urllib.parse import quote, urlencode, urljoin, urlparse, parse_qs
from urllib.request import Request, urlopen


NAVER_ROOT = "https://m.comic.naver.com"
DEFAULT_SOURCE_ROOT = "https://wfwf449.com"
WFWF_PATTERN = re.compile(r"^https?://wfwf(\d+)\.com(?:/cm)?/?$")
WFWF_DEFAULT_NUMBER = 449
WFWF_FORWARD_SCAN_LIMIT = 300
WFWF_BACKWARD_SCAN_LIMIT = 5
WFWF_PARALLEL_PROBES = 10
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0 Safari/537.36"
)

NAVER_GENRES: Dict[str, List[str]] = {
    "EPISODE": ["스토리"],
    "OMNIBUS": ["스토리"],
    "STORY": ["스토리"],
    "DAILY": ["일상"],
    "COMIC": ["개그"],
    "FANTASY": ["판타지"],
    "ACTION": ["액션"],
    "DRAMA": ["드라마"],
    "ROMANCE": ["로맨스"],
    "SENSIBILITY": ["드라마"],
    "THRILL": ["스릴러"],
    "HISTORICAL": ["무협"],
    "SPORTS": ["스포츠"],
}

WEBTOON_GENRES = [
    "성인",
    "드라마",
    "판타지",
    "액션",
    "로맨스",
    "일상",
    "개그",
    "미스터리",
    "순정",
    "스포츠",
    "BL",
    "스릴러",
    "무협",
    "학원",
    "공포",
    "스토리",
]
WEBTOON_GENRE_SET = set(WEBTOON_GENRES)

WEBTOON_ALPHABETS = [
    "ㄱ",
    "ㄴ",
    "ㄷ",
    "ㄹ",
    "ㅁ",
    "ㅂ",
    "ㅅ",
    "ㅇ",
    "ㅈ",
    "ㅊ",
    "ㅋ",
    "ㅌ",
    "ㅍ",
    "ㅎ",
    "a",
    "0",
]

VOID_TAGS = {
    "area",
    "base",
    "br",
    "col",
    "embed",
    "hr",
    "img",
    "input",
    "link",
    "meta",
    "param",
    "source",
    "track",
    "wbr",
}


@dataclass
class NaverTitle:
    title_id: int
    name: str = ""
    tags: List[str] = field(default_factory=list)


@dataclass
class SourceTitle:
    toon_id: int
    name: str
    thumb: str = ""
    release: str = ""
    source_tags: List[str] = field(default_factory=list)
    description: str = ""


class AnchorParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.anchors: List[Tuple[str, str, str]] = []
        self._href: Optional[str] = None
        self._title: str = ""
        self._text: List[str] = []

    def handle_starttag(self, tag: str, attrs: List[Tuple[str, Optional[str]]]) -> None:
        if tag != "a":
            return
        attr = dict(attrs)
        href = attr.get("href") or ""
        if "titleId=" not in href:
            return
        self._href = href
        self._title = attr.get("title") or ""
        self._text = []

    def handle_data(self, data: str) -> None:
        if self._href is not None:
            self._text.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag == "a" and self._href is not None:
            self.anchors.append((self._href, self._title, normalize_space(" ".join(self._text))))
            self._href = None
            self._title = ""
            self._text = []


class SourceParser(HTMLParser):
    def __init__(self, root: str) -> None:
        super().__init__()
        self.root = root
        self.titles: Dict[int, SourceTitle] = {}
        self._collecting = False
        self._depth = 0
        self._toon_id = 0
        self._link_title = ""
        self._thumb = ""
        self._subject_depth = 0
        self._subject_text: List[str] = []
        self._item_text: List[str] = []

    def handle_starttag(self, tag: str, attrs: List[Tuple[str, Optional[str]]]) -> None:
        attr = dict(attrs)
        class_name = attr.get("class") or ""
        should_collect = tag == "li" or (tag == "article" and "searchItem" in class_name)
        if should_collect and not self._collecting:
            self._collecting = True
            self._depth = 1
            self._toon_id = 0
            self._link_title = ""
            self._thumb = ""
            self._subject_depth = 0
            self._subject_text = []
            self._item_text = []
        elif self._collecting and tag not in VOID_TAGS:
            self._depth += 1

        if not self._collecting:
            return

        href = attr.get("href") or ""
        if "toon=" in href and self._toon_id == 0:
            query = parse_qs(urlparse(href).query)
            try:
                self._toon_id = int(query.get("toon", ["0"])[0])
            except ValueError:
                self._toon_id = 0
            self._link_title = attr.get("title") or self._link_title

        if tag == "img":
            thumb = attr.get("data-original") or attr.get("src") or ""
            if thumb and not self._thumb:
                self._thumb = urljoin(self.root, thumb)

        if (tag == "p" and "subject" in class_name) or (
            tag == "h6" and "searchDetailTitle" in class_name
        ):
            self._subject_depth = self._depth

    def handle_data(self, data: str) -> None:
        if not self._collecting:
            return
        text = normalize_space(data)
        if not text:
            return
        self._item_text.append(text)
        if self._subject_depth:
            self._subject_text.append(text)

    def handle_endtag(self, tag: str) -> None:
        if not self._collecting:
            return
        if self._subject_depth == self._depth:
            self._subject_depth = 0
        self._depth -= 1
        if self._depth > 0:
            return
        self._finish_item()
        self._collecting = False

    def _finish_item(self) -> None:
        if self._toon_id <= 0:
            return
        name = clean_source_name(" ".join(self._subject_text)) or clean_source_name(self._link_title)
        if not name:
            name = clean_source_name(guess_source_name(self._item_text))
        if not name:
            return
        self.titles.setdefault(self._toon_id, SourceTitle(self._toon_id, name, self._thumb, ""))


def normalize_space(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def normalize_title(value: str) -> str:
    value = unicodedata.normalize("NFKC", value or "").lower()
    value = re.sub(r"[\[(（]?네이버[\])）]?", "", value)
    value = re.sub(r"[\[(（]?(시즌|season)\s*\d+[\])）]?", "", value)
    value = re.sub(r"[\[(（]?(외전|완전판|개정판|리마스터|리메이크|컬러판|소장판)[\])）]?", "", value)
    value = re.sub(r"\b\d+\s*(부|기|시즌)\b", "", value)
    value = re.sub(r"(시즌|season)\s*\d+", "", value)
    value = re.split(r"[:：\-–—]", value, maxsplit=1)[0]
    value = re.sub(r"\s+", "", value)
    value = re.sub(r"[\[\]\(\){}<>〈〉《》「」『』:：,，.!?~ㆍ·'\"“”‘’_-]", "", value)
    value = re.sub(r"(완결|휴재|신작|new|up)$", "", value)
    return value


def guess_source_name(parts: Iterable[str]) -> str:
    for text in parts:
        text = normalize_space(text)
        if text and text not in {"NEW", "UP", "완결", "네이버웹툰", "작가"}:
            return text
    return ""


def clean_source_name(value: str) -> str:
    value = normalize_space(value)
    value = re.sub(r"\s*/[가-힣A-Za-z0-9]{1,6}$", "", value)
    value = re.sub(r"\s*/[가-힣A-Za-z0-9]{1,6}\s+", " ", value)
    value = re.sub(r"\s*[\[(（]네이버[\])）]\s*", "", value)
    return normalize_space(value)


def http_get(url: str, delay: float, timeout: int = 20) -> str:
    if delay > 0:
        time.sleep(delay)
    req = Request(url, headers={"User-Agent": USER_AGENT})
    with urlopen(req, timeout=timeout) as response:
        raw = response.read()
        charset = response.headers.get_content_charset()
        if "comic.naver.com" in urlparse(url).netloc:
            charset = "utf-8"
        if not charset:
            head = raw[:2048].decode("ascii", errors="ignore")
            match = re.search(r"charset=[\"']?([A-Za-z0-9_-]+)", head, re.IGNORECASE)
            charset = match.group(1) if match else "utf-8"
        text = raw.decode(charset, errors="replace")
        if text.count("\ufffd") > max(5, len(text) // 200):
            fallback = "utf-8" if charset.lower() != "utf-8" else "euc-kr"
            text = raw.decode(fallback, errors="replace")
        return text


def normalize_source_root(root: str) -> str:
    root = (root or DEFAULT_SOURCE_ROOT).strip()
    while root.endswith("/"):
        root = root[:-1]
    if root.endswith("/cm"):
        root = root[:-3]
    return root or DEFAULT_SOURCE_ROOT


def source_root_number(root: str) -> int:
    match = WFWF_PATTERN.match(normalize_source_root(root))
    if not match:
        return -1
    try:
        return int(match.group(1))
    except ValueError:
        return -1


def is_wfwf_source_root(root: str) -> bool:
    return source_root_number(root) > 0


def source_root_candidates(current: int) -> List[int]:
    numbers: List[int] = []
    seen = set()

    def add(number: int) -> None:
        if number > 0 and number not in seen:
            seen.add(number)
            numbers.append(number)

    for offset in range(1, WFWF_FORWARD_SCAN_LIMIT + 1):
        add(current + offset)
    add(WFWF_DEFAULT_NUMBER)
    for offset in range(1, WFWF_FORWARD_SCAN_LIMIT + 1):
        add(WFWF_DEFAULT_NUMBER + offset)
    for offset in range(1, WFWF_BACKWARD_SCAN_LIMIT + 1):
        add(current - offset)
    return numbers


def looks_like_source_root(html: str) -> bool:
    lower = (html or "").lower()
    return any(
        marker in lower
        for marker in (
            "webtoon-list",
            "toon=",
            "/view?toon=",
            "/list?toon=",
            "/cv?toon=",
            "/cl?toon=",
        )
    )


def probe_source_root(root: str) -> bool:
    root = normalize_source_root(root)
    for path in ("/ing", "/cm"):
        try:
            html = http_get(urljoin(root.rstrip("/") + "/", path.lstrip("/")), delay=0, timeout=6)
            if looks_like_source_root(html):
                return True
        except Exception:
            continue
    return False


def resolve_source_root(root: str, auto_resolve: bool) -> str:
    root = normalize_source_root(root)
    if not auto_resolve or not is_wfwf_source_root(root):
        return root
    if probe_source_root(root):
        return root

    current = source_root_number(root)
    if current <= 0:
        current = WFWF_DEFAULT_NUMBER
    candidates = source_root_candidates(current)
    for start in range(0, len(candidates), WFWF_PARALLEL_PROBES):
        chunk = candidates[start : start + WFWF_PARALLEL_PROBES]
        with ThreadPoolExecutor(max_workers=WFWF_PARALLEL_PROBES) as executor:
            futures = {
                executor.submit(probe_source_root, f"https://wfwf{number}.com"): number
                for number in chunk
            }
            for future in as_completed(futures):
                if future.result():
                    return f"https://wfwf{futures[future]}.com"
    return root


def naver_genre_url(genre: str, sort: str, page: int) -> str:
    query = {"genre": genre, "sort": sort}
    if page > 1:
        query["page"] = str(page)
    return f"{NAVER_ROOT}/webtoon/genre?{urlencode(query)}"


def title_id_from_href(href: str) -> int:
    try:
        return int(parse_qs(urlparse(href).query).get("titleId", ["0"])[0])
    except ValueError:
        return 0


def clean_naver_anchor_title(title_attr: str, text: str) -> str:
    title = normalize_space(title_attr)
    if title:
        return title
    text = normalize_space(text)
    text = re.split(r"\s+(up|new|휴재|완결|별점)\b", text, maxsplit=1, flags=re.IGNORECASE)[0]
    return normalize_space(text)


def parse_sorts(value: str) -> List[str]:
    result: List[str] = []
    for item in re.split(r"[, ]+", value.upper().strip()):
        if not item:
            continue
        if item not in {"UPDATE", "HIT", "NEW"}:
            raise ValueError(f"unsupported sort: {item}")
        if item not in result:
            result.append(item)
    return result or ["UPDATE"]


def fetch_naver_titles(
    max_pages: int, sorts: Iterable[str], delay: float, fetch_details: bool, workers: int
) -> Dict[int, NaverTitle]:
    titles: Dict[int, NaverTitle] = {}
    tasks = [
        (sort, genre, mapped_tags, page)
        for sort in sorts
        for genre, mapped_tags in NAVER_GENRES.items()
        for page in range(1, max_pages + 1)
    ]
    with ThreadPoolExecutor(max_workers=max(1, workers)) as executor:
        futures = {
            executor.submit(fetch_naver_genre_page, genre, mapped_tags, sort, page, delay): (genre, page)
            for sort, genre, mapped_tags, page in tasks
        }
        for future in as_completed(futures):
            try:
                page_titles = future.result()
            except Exception:
                continue
            for title_id, name, mapped_tags in page_titles:
                item = titles.setdefault(title_id, NaverTitle(title_id))
                for tag in mapped_tags:
                    if tag not in item.tags:
                        item.tags.append(tag)
                if name and (not item.name or len(name) < len(item.name)):
                    item.name = name

    if fetch_details:
        for item in titles.values():
            if item.name:
                continue
            name = fetch_naver_detail_name(item.title_id, delay)
            if name:
                item.name = name
    return {k: v for k, v in titles.items() if v.name and v.tags}


def fetch_naver_genre_page(
    genre: str, mapped_tags: List[str], sort: str, page: int, delay: float
) -> List[Tuple[int, str, List[str]]]:
    html = http_get(naver_genre_url(genre, sort, page), delay)
    parser = AnchorParser()
    parser.feed(html)
    result: List[Tuple[int, str, List[str]]] = []
    for href, title_attr, text in parser.anchors:
        title_id = title_id_from_href(href)
        if title_id <= 0:
            continue
        result.append((title_id, clean_naver_anchor_title(title_attr, text), mapped_tags))
    return result


def fetch_naver_detail_name(title_id: int, delay: float) -> str:
    try:
        html = http_get(f"{NAVER_ROOT}/webtoon/list?titleId={title_id}", delay)
    except URLError:
        return ""
    match = re.search(r"<title>\s*([^:<]+)", html, re.IGNORECASE)
    if match:
        return normalize_space(match.group(1))
    og = re.search(r'<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']+)', html)
    return normalize_space(og.group(1)) if og else ""


def webtoon_day_path(status: str, value: str, order: str) -> str:
    return f"/{status}?type1=day&type2={quote(value, encoding='euc-kr')}&o={order}"


def webtoon_genre_path(status: str, genre: str, order: str) -> str:
    if genre == "성인":
        return f"/{status}?type1=genre&o={order}"
    return f"/{status}?type1=genre&type2={quote(genre, encoding='euc-kr')}&o={order}"


def webtoon_alphabet_path(status: str, value: str, order: str) -> str:
    return f"/{status}?type1=alphabet&type2={quote(value, encoding='euc-kr')}&o={order}"


def source_paths() -> List[Tuple[str, Optional[str]]]:
    paths: List[Tuple[str, Optional[str]]] = []
    for status in ("ing", "end"):
        paths.extend(
            [
                (webtoon_day_path(status, "recent", "n"), None),
                (webtoon_day_path(status, "new", "n"), None),
                (f"/{status}?type1=genre&type2=&o=f" if status == "end" else f"/{status}?type1=day&type2=recent&o=f", None),
            ]
        )
        for genre in WEBTOON_GENRES:
            paths.append((webtoon_genre_path(status, genre, "n"), genre))
        for alphabet in WEBTOON_ALPHABETS:
            paths.append((webtoon_alphabet_path(status, alphabet, "n"), None))
    return list(dict.fromkeys(paths))


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


def fetch_source_path_titles(root: str, path: str, delay: float) -> Dict[int, SourceTitle]:
    html = http_get(urljoin(root.rstrip("/") + "/", path.lstrip("/")), delay)
    parser = SourceParser(root)
    parser.feed(html)
    return parser.titles


def fetch_source_titles_sequential(root: str, delay: float, max_paths: int) -> Dict[int, SourceTitle]:
    titles: Dict[int, SourceTitle] = {}
    paths = source_paths()
    if max_paths > 0:
        paths = paths[:max_paths]
    for path, genre in paths:
        try:
            parsed = fetch_source_path_titles(root, path, delay)
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


def enrich_source_details(
    root: str,
    titles: Dict[int, SourceTitle],
    delay: float,
    workers: int,
    mode: str,
    cache_path: Optional[Path],
) -> None:
    detail_cache = load_detail_cache(cache_path)
    candidates = list(titles.values()) if mode == "all" else [title for title in titles.values() if not title.source_tags]
    targets: List[SourceTitle] = []
    for title in candidates:
        cached = detail_cache.get(str(title.toon_id))
        if cached and apply_cached_detail(title, cached):
            continue
        targets.append(title)
    print(f"detail cache hits: {len(candidates) - len(targets)} fetches: {len(targets)}", file=sys.stderr)
    total = len(targets)
    if total == 0:
        return
    completed = 0
    with ThreadPoolExecutor(max_workers=max(1, workers)) as executor:
        futures = {executor.submit(fetch_source_detail_tags, root, title, delay): title for title in targets}
        for future in as_completed(futures):
            title = futures[future]
            try:
                detail_tags, description = future.result()
            except Exception:
                detail_tags, description = [], ""
            if detail_tags:
                title.source_tags = detail_tags
            if description:
                title.description = description
            detail_cache[str(title.toon_id)] = {
                "name": title.name,
                "tags": detail_tags,
                "description": description,
            }
            completed += 1
            if completed % 100 == 0 or completed == total:
                print(f"detail progress: {completed}/{total}", file=sys.stderr)
                save_detail_cache(cache_path, detail_cache)
    save_detail_cache(cache_path, detail_cache)


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
        data = {"version": 1, "entries": cache}
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except Exception:
        pass


def apply_cached_detail(title: SourceTitle, cached: object) -> bool:
    if not isinstance(cached, dict):
        return False
    tags = cached.get("tags")
    if not isinstance(tags, list):
        return False
    clean_tags = [str(tag) for tag in tags if str(tag)]
    if clean_tags:
        title.source_tags = clean_tags
    description = cached.get("description")
    if isinstance(description, str) and description:
        title.description = description
    return bool(clean_tags)


def fetch_source_detail_tags(root: str, title: SourceTitle, delay: float) -> Tuple[List[str], str]:
    html = http_get(urljoin(root.rstrip("/") + "/", f"list?toon={title.toon_id}"), delay, timeout=12)
    return parse_source_detail(html)


def parse_source_detail(html: str) -> Tuple[List[str], str]:
    tags: List[str] = []
    match = re.search(
        r"<strong>\s*장르\s*:\s*</strong>\s*([^<]+)",
        html,
        flags=re.IGNORECASE,
    )
    if match:
        for raw in re.split(r"[/,·ㆍ| ]+", match.group(1)):
            for tag in normalize_source_genres(raw):
                if tag and tag not in tags:
                    tags.append(tag)
    desc = ""
    desc_match = re.search(
        r'<meta\s+name=["\']description["\']\s+content=["\']([^"\']*)',
        html,
        flags=re.IGNORECASE,
    )
    if desc_match:
        desc = normalize_space(html_lib.unescape(desc_match.group(1)))
    return tags, desc


def normalize_source_genres(value: str) -> List[str]:
    value = normalize_space(value)
    if not value:
        return []
    lower = value.lower()
    results: List[str] = []
    checks = [
        ("BL", ["비엘", "bl"]),
        ("성인", ["19", "19금", "어른", "성인", "고수위", "야한"]),
        ("로맨스", ["순정", "백합", "로맨스"]),
        ("무협", ["무협", "사극", "역사", "시대극"]),
        ("스토리", ["스토리", "에피소드", "옴니버스"]),
        ("일상", ["일상"]),
        ("개그", ["코믹", "코미디", "개그"]),
        ("드라마", ["드라마", "성장", "연예계", "아이돌"]),
        ("액션", ["액션", "배틀"]),
        ("스릴러", ["스릴러", "범죄"]),
        ("미스터리", ["미스터리", "추리"]),
        ("공포", ["공포", "호러"]),
        ("판타지", ["판타지"]),
        ("스포츠", ["스포츠"]),
        ("학원", ["학원"]),
    ]
    for tag, needles in checks:
        if any(needle.lower() in lower for needle in needles):
            results.append(tag)
    if value in WEBTOON_GENRE_SET and value not in results:
        results.append(value)
    return results


def match_titles(
    source_titles: Dict[int, SourceTitle],
    naver_titles: Dict[int, NaverTitle],
    auto_fuzzy_score: float,
    review_fuzzy_score: float,
) -> Tuple[Dict[int, Tuple[SourceTitle, NaverTitle, str, float]], List[SourceTitle], List[Dict[str, object]]]:
    naver_by_name: Dict[str, NaverTitle] = {}
    ambiguous_names = set()
    naver_candidates: List[Tuple[str, NaverTitle]] = []
    for title in naver_titles.values():
        key = normalize_title(title.name)
        if not key:
            continue
        naver_candidates.append((key, title))
        if key in naver_by_name:
            ambiguous_names.add(key)
        else:
            naver_by_name[key] = title
    for key in ambiguous_names:
        naver_by_name.pop(key, None)

    matched: Dict[int, Tuple[SourceTitle, NaverTitle, str, float]] = {}
    unmatched: List[SourceTitle] = []
    review: List[Dict[str, object]] = []
    for source in source_titles.values():
        source_key = normalize_title(source.name)
        naver = naver_by_name.get(source_key)
        if naver is not None:
            matched[source.toon_id] = (source, naver, "normalized-title", 1.0)
            continue

        candidate = best_fuzzy_candidate(source_key, naver_candidates, review_fuzzy_score)
        if candidate is not None:
            score, fuzzy = candidate
            if score >= auto_fuzzy_score:
                matched[source.toon_id] = (source, fuzzy, "high-confidence-title", score)
            else:
                review.append(
                    {
                        "sourceId": source.toon_id,
                        "sourceName": source.name,
                        "sourceThumb": source.thumb,
                        "naverTitleId": fuzzy.title_id,
                        "naverName": fuzzy.name,
                        "tags": fuzzy.tags,
                        "score": round(score, 4),
                    }
                )
        unmatched.append(source)
    review.sort(key=lambda item: item["score"], reverse=True)
    return matched, unmatched, review


def best_fuzzy_candidate(
    source_key: str, naver_candidates: List[Tuple[str, NaverTitle]], min_score: float
) -> Optional[Tuple[float, NaverTitle]]:
    if len(source_key) < 3:
        return None
    best_score = 0.0
    best_title: Optional[NaverTitle] = None
    second_score = 0.0
    for naver_key, title in naver_candidates:
        if len(naver_key) < 3:
            continue
        if source_key[0] != naver_key[0]:
            continue
        score = SequenceMatcher(None, source_key, naver_key).ratio()
        if source_key in naver_key or naver_key in source_key:
            score = max(score, min(len(source_key), len(naver_key)) / max(len(source_key), len(naver_key)))
        if score > best_score:
            second_score = best_score
            best_score = score
            best_title = title
        elif score > second_score:
            second_score = score
    if best_title is None:
        return None
    if best_score < min_score or best_score - second_score < 0.03:
        return None
    return best_score, best_title


def merge_existing(path: Path) -> Dict[str, object]:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def build_output(
    existing: Dict[str, object],
    matched: Dict[int, Tuple[SourceTitle, NaverTitle, str, float]],
    source_titles: Dict[int, SourceTitle],
    source_root: str,
) -> Dict[str, object]:
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
        previous_source_tags = previous.get("sourceTags") if isinstance(previous.get("sourceTags"), list) else []
        merged_source_tags = source.source_tags if source.source_tags else previous_source_tags
        external_tags = previous.get("externalTags") if isinstance(previous.get("externalTags"), list) else []
        manual_tags = previous.get("manualTags") if isinstance(previous.get("manualTags"), list) else []
        if not merged_source_tags and str(toon_id) not in titles:
            if external_tags or manual_tags:
                pass
            else:
                merged_source_tags = ["미분류"]
        next_item = dict(previous)
        next_item.update(
            {
                "name": source.name,
                "thumb": source.thumb or previous.get("thumb", ""),
                "release": source.release or previous.get("release", ""),
                "description": source.description or previous.get("description", ""),
            }
        )
        if merged_source_tags:
            next_item["sourceTags"] = merged_source_tags
        next_item.pop("inferredTags", None)
        titles[str(toon_id)] = next_item

    for toon_id, (source, naver, match_type, match_score) in matched.items():
        previous = titles.get(str(toon_id), {})
        manual = previous.get("manual", False) if isinstance(previous, dict) else False
        if manual:
            continue
        existing_external = previous.get("externalTags") if isinstance(previous, dict) and isinstance(previous.get("externalTags"), list) else []
        previous_source_tags = previous.get("sourceTags") if isinstance(previous, dict) and isinstance(previous.get("sourceTags"), list) else []
        source_tags = source.source_tags if source.source_tags else previous_source_tags
        source_tags = [tag for tag in source_tags if tag != "미분류"]
        titles[str(toon_id)] = {
            "name": source.name,
            "thumb": source.thumb,
            "release": source.release,
            "description": source.description,
            "externalTags": merge_tags(existing_external, naver.tags),
            "sourceTags": source_tags,
            "tags": merge_tags(naver.tags, source_tags),
            "naverTitleId": naver.title_id,
            "naverName": naver.name,
            "match": match_type,
            "matchScore": round(match_score, 4),
        }

    return {
        "version": int(existing.get("version", 1)) if existing else 1,
        "updated": date.today().isoformat(),
        "sourceRoot": source_root,
        "sources": ["naver-webtoon-genre", "source-title-match"],
        "titles": dict(sorted(titles.items(), key=lambda item: int(item[0]))),
    }


def merge_tags(*groups: Iterable[str]) -> List[str]:
    result: List[str] = []
    for group in groups:
        for tag in group or []:
            if tag and tag not in result:
                result.append(tag)
    return result


def write_unmatched(path: Path, unmatched: List[SourceTitle]) -> None:
    data = [
        {"id": item.toon_id, "name": item.name, "thumb": item.thumb}
        for item in unmatched
    ]
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_review(path: Path, review: List[Dict[str, object]]) -> None:
    path.write_text(json.dumps(review, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_unclassified(path: Path, data: Dict[str, object]) -> None:
    titles = data.get("titles", {}) if isinstance(data.get("titles"), dict) else {}
    unclassified = []
    for key, item in titles.items():
        if not isinstance(item, dict):
            continue
        has_tags = False
        for field in ("manualTags", "externalTags", "sourceTags", "inferredTags", "tags"):
            values = item.get(field)
            if isinstance(values, list) and any(tag and tag != "미분류" for tag in values):
                has_tags = True
                break
        if not has_tags:
            unclassified.append(
                {
                    "id": int(key),
                    "name": item.get("name", ""),
                    "thumb": item.get("thumb", ""),
                }
            )
    path.write_text(json.dumps(unclassified, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def resolve_output(output: Path, asset_output: Path, external_evidence: str = "") -> None:
    resolver = Path(__file__).resolve().parent / "classification" / "resolve_classification.py"
    command = [
            sys.executable,
            str(resolver),
            "--kind",
            "webtoon",
            "--input",
            str(output),
            "--output",
            str(output),
            "--asset-output",
            str(asset_output),
    ]
    if external_evidence:
        command.extend(["--external-evidence", external_evidence])
    subprocess.run(command, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Build webtoon-classification.json from Naver genres.")
    parser.add_argument("--output", default="webtoon-classification.json")
    parser.add_argument("--asset-output", default="app/src/main/assets/webtoon-classification.json")
    parser.add_argument("--no-resolve", action="store_true")
    parser.add_argument("--external-evidence", default="tools/classification/webtoon-external-evidence.jsonl")
    parser.add_argument("--source-root", default=DEFAULT_SOURCE_ROOT)
    parser.add_argument("--no-source-root-resolve", action="store_true")
    parser.add_argument("--max-naver-pages", type=int, default=10)
    parser.add_argument("--max-source-paths", type=int, default=0, help="0 means all configured source paths")
    parser.add_argument("--sorts", default="UPDATE,HIT,NEW", help="Comma-separated Naver sort modes: UPDATE,HIT,NEW")
    parser.add_argument("--delay", type=float, default=0.35)
    parser.add_argument("--naver-workers", type=int, default=16)
    parser.add_argument("--source-workers", type=int, default=16)
    parser.add_argument("--no-detail-fetch", action="store_true")
    parser.add_argument("--unmatched-output", default="")
    parser.add_argument("--review-output", default="")
    parser.add_argument("--unclassified-output", default="")
    parser.add_argument("--no-source-detail-fetch", action="store_true")
    parser.add_argument("--source-detail-workers", type=int, default=12)
    parser.add_argument("--source-detail-mode", choices=("missing", "all"), default="all")
    parser.add_argument("--source-detail-cache", default=".webtoon-source-detail-cache.json")
    parser.add_argument("--no-source-detail-cache", action="store_true")
    parser.add_argument("--auto-fuzzy-score", type=float, default=0.955)
    parser.add_argument("--review-fuzzy-score", type=float, default=0.90)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--allow-empty-source", action="store_true")
    args = parser.parse_args()

    output = Path(args.output)
    existing = merge_existing(output)
    sorts = parse_sorts(args.sorts)
    source_root = resolve_source_root(args.source_root, auto_resolve=not args.no_source_root_resolve)
    if source_root != normalize_source_root(args.source_root):
        print(f"resolved source root: {args.source_root} -> {source_root}", file=sys.stderr)

    print(f"fetching naver genre data ({','.join(sorts)})...", file=sys.stderr)
    naver_titles = fetch_naver_titles(
        max_pages=args.max_naver_pages,
        sorts=sorts,
        delay=args.delay,
        fetch_details=not args.no_detail_fetch,
        workers=args.naver_workers,
    )
    print(f"naver titles: {len(naver_titles)}", file=sys.stderr)

    print("fetching source titles...", file=sys.stderr)
    source_titles = fetch_source_titles(source_root, args.delay, args.max_source_paths, args.source_workers)
    print(f"source titles: {len(source_titles)}", file=sys.stderr)
    if not source_titles and not args.allow_empty_source:
        raise RuntimeError("source title fetch returned 0 titles; refusing to overwrite classification DB")
    if not args.no_source_detail_fetch:
        detail_targets = len(source_titles) if args.source_detail_mode == "all" else sum(1 for title in source_titles.values() if not title.source_tags)
        print(f"fetching source detail genres ({args.source_detail_mode}): {detail_targets}", file=sys.stderr)
        enrich_source_details(
            source_root,
            source_titles,
            args.delay,
            args.source_detail_workers,
            args.source_detail_mode,
            None if args.no_source_detail_cache else Path(args.source_detail_cache),
        )

    matched, unmatched, review = match_titles(
        source_titles,
        naver_titles,
        auto_fuzzy_score=args.auto_fuzzy_score,
        review_fuzzy_score=args.review_fuzzy_score,
    )
    print(f"matched: {len(matched)} unmatched: {len(unmatched)} review: {len(review)}", file=sys.stderr)

    data = build_output(existing, matched, source_titles, source_root)
    text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    if args.dry_run:
        print(text)
    else:
        output.write_text(text, encoding="utf-8")
        if not args.no_resolve:
            resolve_output(output, Path(args.asset_output), args.external_evidence if Path(args.external_evidence).exists() else "")
        if args.unmatched_output:
            write_unmatched(Path(args.unmatched_output), unmatched)
        if args.review_output:
            write_review(Path(args.review_output), review)
        if args.unclassified_output:
            write_unclassified(Path(args.unclassified_output), data)
        print(f"wrote {output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
