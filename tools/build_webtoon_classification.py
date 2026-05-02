#!/usr/bin/env python3
"""
Build webtoon-classification.json from Naver Webtoon genre pages.

The Android app reads only the generated JSON. This script is an offline
maintenance tool so genre lookup does not happen on user devices.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import unicodedata
from dataclasses import dataclass, field
from datetime import date
from html.parser import HTMLParser
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple
from urllib.error import URLError
from urllib.parse import quote, urlencode, urljoin, urlparse, parse_qs
from urllib.request import Request, urlopen


NAVER_ROOT = "https://m.comic.naver.com"
DEFAULT_SOURCE_ROOT = "https://wfwf449.com"
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


def fetch_naver_titles(max_pages: int, sort: str, delay: float, fetch_details: bool) -> Dict[int, NaverTitle]:
    titles: Dict[int, NaverTitle] = {}
    for genre, mapped_tags in NAVER_GENRES.items():
        for page in range(1, max_pages + 1):
            url = naver_genre_url(genre, sort, page)
            html = http_get(url, delay)
            parser = AnchorParser()
            parser.feed(html)
            page_ids = 0
            for href, title_attr, text in parser.anchors:
                title_id = title_id_from_href(href)
                if title_id <= 0:
                    continue
                page_ids += 1
                item = titles.setdefault(title_id, NaverTitle(title_id))
                for tag in mapped_tags:
                    if tag not in item.tags:
                        item.tags.append(tag)
                if not item.name:
                    item.name = clean_naver_anchor_title(title_attr, text)
            if page_ids == 0:
                break

    if fetch_details:
        for item in titles.values():
            if item.name:
                continue
            name = fetch_naver_detail_name(item.title_id, delay)
            if name:
                item.name = name
    return {k: v for k, v in titles.items() if v.name and v.tags}


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


def source_paths() -> List[str]:
    paths: List[str] = []
    for status in ("ing", "end"):
        paths.extend(
            [
                webtoon_day_path(status, "recent", "n"),
                webtoon_day_path(status, "new", "n"),
                f"/{status}?type1=genre&type2=&o=f" if status == "end" else f"/{status}?type1=day&type2=recent&o=f",
            ]
        )
        for genre in WEBTOON_GENRES:
            paths.append(webtoon_genre_path(status, genre, "n"))
    return list(dict.fromkeys(paths))


def fetch_source_titles(root: str, delay: float, max_paths: int) -> Dict[int, SourceTitle]:
    titles: Dict[int, SourceTitle] = {}
    paths = source_paths()
    if max_paths > 0:
        paths = paths[:max_paths]
    for path in paths:
        try:
            html = http_get(urljoin(root.rstrip("/") + "/", path.lstrip("/")), delay)
        except Exception as exc:
            print(f"warn: source fetch failed: {path}: {exc}", file=sys.stderr)
            continue
        parser = SourceParser(root)
        parser.feed(html)
        titles.update(parser.titles)
    return titles


def match_titles(
    source_titles: Dict[int, SourceTitle], naver_titles: Dict[int, NaverTitle]
) -> Tuple[Dict[int, Tuple[SourceTitle, NaverTitle]], List[SourceTitle]]:
    naver_by_name: Dict[str, NaverTitle] = {}
    ambiguous_names = set()
    for title in naver_titles.values():
        key = normalize_title(title.name)
        if not key:
            continue
        if key in naver_by_name:
            ambiguous_names.add(key)
        else:
            naver_by_name[key] = title
    for key in ambiguous_names:
        naver_by_name.pop(key, None)

    matched: Dict[int, Tuple[SourceTitle, NaverTitle]] = {}
    unmatched: List[SourceTitle] = []
    for source in source_titles.values():
        naver = naver_by_name.get(normalize_title(source.name))
        if naver is None:
            unmatched.append(source)
            continue
        matched[source.toon_id] = (source, naver)
    return matched, unmatched


def merge_existing(path: Path) -> Dict[str, object]:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def build_output(
    existing: Dict[str, object],
    matched: Dict[int, Tuple[SourceTitle, NaverTitle]],
    source_root: str,
) -> Dict[str, object]:
    titles = dict(existing.get("titles", {})) if isinstance(existing.get("titles"), dict) else {}
    for toon_id, (source, naver) in matched.items():
        previous = titles.get(str(toon_id), {})
        manual = previous.get("manual", False) if isinstance(previous, dict) else False
        if manual:
            continue
        titles[str(toon_id)] = {
            "name": source.name,
            "thumb": source.thumb,
            "release": source.release,
            "tags": naver.tags,
            "naverTitleId": naver.title_id,
            "naverName": naver.name,
            "match": "normalized-title",
        }

    return {
        "version": int(existing.get("version", 1)) if existing else 1,
        "updated": date.today().isoformat(),
        "sourceRoot": source_root,
        "sources": ["naver-webtoon-genre", "source-title-match"],
        "titles": dict(sorted(titles.items(), key=lambda item: int(item[0]))),
    }


def write_unmatched(path: Path, unmatched: List[SourceTitle]) -> None:
    data = [
        {"id": item.toon_id, "name": item.name, "thumb": item.thumb}
        for item in unmatched
    ]
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build webtoon-classification.json from Naver genres.")
    parser.add_argument("--output", default="webtoon-classification.json")
    parser.add_argument("--source-root", default=DEFAULT_SOURCE_ROOT)
    parser.add_argument("--max-naver-pages", type=int, default=3)
    parser.add_argument("--max-source-paths", type=int, default=0, help="0 means all configured source paths")
    parser.add_argument("--sort", default="UPDATE", choices=["UPDATE", "HIT", "NEW"])
    parser.add_argument("--delay", type=float, default=0.35)
    parser.add_argument("--no-detail-fetch", action="store_true")
    parser.add_argument("--unmatched-output", default="")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    output = Path(args.output)
    existing = merge_existing(output)

    print("fetching naver genre data...", file=sys.stderr)
    naver_titles = fetch_naver_titles(
        max_pages=args.max_naver_pages,
        sort=args.sort,
        delay=args.delay,
        fetch_details=not args.no_detail_fetch,
    )
    print(f"naver titles: {len(naver_titles)}", file=sys.stderr)

    print("fetching source titles...", file=sys.stderr)
    source_titles = fetch_source_titles(args.source_root, args.delay, args.max_source_paths)
    print(f"source titles: {len(source_titles)}", file=sys.stderr)

    matched, unmatched = match_titles(source_titles, naver_titles)
    print(f"matched: {len(matched)} unmatched: {len(unmatched)}", file=sys.stderr)

    data = build_output(existing, matched, args.source_root)
    text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    if args.dry_run:
        print(text)
    else:
        output.write_text(text, encoding="utf-8")
        if args.unmatched_output:
            write_unmatched(Path(args.unmatched_output), unmatched)
        print(f"wrote {output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
